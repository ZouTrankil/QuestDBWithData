package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.api.ReadSupport;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;

/** In-process scalar Parquet decoder shared by the L2 manifest, daily, minute and label contracts. */
public final class NativeL2ParquetReader {
    private NativeL2ParquetReader() {}

    @FunctionalInterface public interface Check { void run() throws Exception; }
    @FunctionalInterface public interface RowConsumer { void accept(ObjectNode row) throws Exception; }

    public static Map<String, String> schema(Path file) throws IOException {
        requireFile(file);
        try (var reader = ParquetFileReader.open(new LocalInputFile(file),
                ParquetReadOptions.builder(new PlainParquetConfiguration()).build())) {
            return schema(reader.getFooter().getFileMetaData().getSchema());
        }
    }

    public static long read(Path file, Map<String, String> expected, long maxRows,
            Check check, RowConsumer consumer) throws Exception {
        requireFile(file);
        if (maxRows < 1) throw new IllegalArgumentException("Positive Parquet row budget required");
        check.run();
        var actual = schema(file);
        if (expected != null && (!actual.equals(expected)
                || !java.util.List.copyOf(actual.keySet()).equals(java.util.List.copyOf(expected.keySet()))))
            throw new IOException("L2 Parquet scalar schema differs from the frozen field contract");
        try (ParquetReader<Group> reader = new ParquetReader.Builder<Group>(
                new LocalInputFile(file), new PlainParquetConfiguration()) {
            @Override protected ReadSupport<Group> getReadSupport() { return new GroupReadSupport(); }
        }.build()) {
            long rows = 0;
            Group group;
            while ((group = reader.read()) != null) {
                check.run();
                if (++rows > maxRows) throw new IOException("L2 Parquet decoded rows exceed their finite budget");
                consumer.accept(decode(group));
            }
            check.run();
            return rows;
        }
    }

    private static void requireFile(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))
            throw new IOException("L2 Parquet source is missing or linked");
    }

    private static Map<String, String> schema(MessageType type) throws IOException {
        var fields = new LinkedHashMap<String, String>();
        for (Type field : type.getFields()) {
            if (!field.isPrimitive() || field.isRepetition(Type.Repetition.REPEATED))
                throw new IOException("L2 Parquet contract requires non-repeated scalar fields");
            var primitive = field.asPrimitiveType();
            var annotation = primitive.getLogicalTypeAnnotation();
            String logical = switch (primitive.getPrimitiveTypeName()) {
                case BOOLEAN -> "bool";
                case DOUBLE -> "double";
                case FLOAT -> "float";
                case INT32 -> {
                    if (annotation == null || annotation instanceof LogicalTypeAnnotation.IntLogicalTypeAnnotation integer
                            && integer.isSigned() && integer.getBitWidth() == 32) yield "int32";
                    throw new IOException("Unsupported L2 Parquet int32 logical type");
                }
                case INT64 -> {
                    if (annotation instanceof LogicalTypeAnnotation.TimestampLogicalTypeAnnotation stamp)
                        yield "timestamp[" + switch (stamp.getUnit()) {
                            case NANOS -> "ns"; case MICROS -> "us"; case MILLIS -> "ms";
                        } + (stamp.isAdjustedToUTC() ? ", tz=UTC" : "") + "]";
                    if (annotation == null || annotation instanceof LogicalTypeAnnotation.IntLogicalTypeAnnotation integer
                            && integer.isSigned() && integer.getBitWidth() == 64) yield "int64";
                    throw new IOException("Unsupported L2 Parquet int64 logical type");
                }
                case BINARY -> {
                    if (annotation instanceof LogicalTypeAnnotation.StringLogicalTypeAnnotation) yield "string";
                    throw new IOException("L2 Parquet binary field must be UTF-8 text");
                }
                default -> throw new IOException("Unsupported L2 Parquet scalar type: " + primitive.getPrimitiveTypeName());
            };
            fields.put(field.getName(), logical);
        }
        return fields;
    }

    private static ObjectNode decode(Group group) throws IOException {
        var row = JsonNodeFactory.instance.objectNode();
        for (int index = 0; index < group.getType().getFieldCount(); index++) {
            var field = group.getType().getType(index).asPrimitiveType();
            String name = field.getName();
            int count = group.getFieldRepetitionCount(index);
            if (count == 0) { row.putNull(name); continue; }
            if (count != 1) throw new IOException("L2 Parquet field is not a scalar");
            switch (field.getPrimitiveTypeName()) {
                case BOOLEAN -> row.put(name, group.getBoolean(index, 0));
                case INT32 -> row.put(name, group.getInteger(index, 0));
                case FLOAT -> {
                    float value = group.getFloat(index, 0);
                    if (Float.isNaN(value)) row.putNull(name);
                    else if (!Float.isFinite(value)) throw new IOException("L2 Parquet contains an infinite floating-point value");
                    else row.put(name, value);
                }
                case BINARY -> row.put(name, group.getBinary(index, 0).toStringUsingUTF8());
                case DOUBLE -> {
                    double value = group.getDouble(index, 0);
                    if (Double.isNaN(value)) row.putNull(name);
                    else if (!Double.isFinite(value)) throw new IOException("L2 Parquet contains an infinite floating-point value");
                    else row.put(name, value);
                }
                case INT64 -> {
                    long value = group.getLong(index, 0);
                    if (field.getLogicalTypeAnnotation() instanceof LogicalTypeAnnotation.TimestampLogicalTypeAnnotation stamp)
                        row.put(name, timestamp(value, stamp));
                    else row.put(name, value);
                }
                default -> throw new IOException("Unsupported L2 Parquet primitive value");
            }
        }
        return row;
    }

    private static String timestamp(long value, LogicalTypeAnnotation.TimestampLogicalTypeAnnotation type) {
        long unitsPerSecond = switch (type.getUnit()) {
            case NANOS -> 1_000_000_000L; case MICROS -> 1_000_000L; case MILLIS -> 1_000L;
        };
        long seconds = Math.floorDiv(value, unitsPerSecond);
        int nanos = Math.toIntExact(Math.floorMod(value, unitsPerSecond) * (1_000_000_000L / unitsPerSecond));
        var instant = Instant.ofEpochSecond(seconds, nanos);
        return type.isAdjustedToUTC() ? instant.toString()
                : LocalDateTime.ofInstant(instant, ZoneOffset.UTC).toString();
    }
}
