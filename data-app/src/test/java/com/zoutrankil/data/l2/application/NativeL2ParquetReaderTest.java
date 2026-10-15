package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.L2DailyFeatureField;
import com.zoutrankil.data.domain.L2DailyFeatures;
import com.zoutrankil.data.service.SyncJobRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual compressed Parquet files exercise the native decoder and manifest certification boundary. */
class NativeL2ParquetReaderTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);
    private static final String SYMBOL = "000001.SZ";
    @TempDir Path root;

    @Test void snappyDailyReadsAll110FieldsAndManifestEvidenceWithoutInterpreter() throws Exception {
        fixture(true, false, false);
        var source = new L2DailyFeaturesParquetSource(root);
        var inspection = source.inspect(DAY, DAY, List.of(SYMBOL), 100, 10);
        assertEquals(1, inspection.selectedRows()); assertEquals(2, inspection.files());
        assertEquals(64, inspection.sourceFingerprint().length());
        var pages = new ArrayList<SyncJobRunner.Page<L2DailyFeatures>>();
        var completion = source.stream(inspection, List.of(SYMBOL), 100, 10, pages::add, () -> false, Duration.ofSeconds(30));
        assertTrue(completion.complete()); assertEquals(1, completion.rows());
        var row = pages.getFirst().rows().getFirst();
        assertEquals(DAY, row.tradeDate()); assertEquals(SYMBOL, row.symbol());
        assertEquals(108, row.features().size());
        assertEquals(9L, row.features().get(L2DailyFeatureField.DEAL_RECORDS));
        assertEquals(true, row.features().get(L2DailyFeatureField.HAS_DEAL));
        assertNull(row.features().get(L2DailyFeatureField.MFI_SCORE));
        assertTrue(pages.getFirst().responseEvidence().contains("java-parquet-1.17.1"));
        assertNotEquals(inspection.sourceFingerprint(), pages.getFirst().sourceFingerprint());
    }

    @Test void readsIndependentPyarrow21ReferenceFixtureWithAll110Fields() throws Exception {
        fixture(true, false, false);
        Path feature = partition("l2_daily_features").resolve("part-00000.parquet");
        try (var reference = getClass().getResourceAsStream("/l2/pyarrow-21-daily-snappy.parquet")) {
            assertNotNull(reference);
            Files.copy(reference, feature, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        var source = new L2DailyFeaturesParquetSource(root);
        var inspection = source.inspect(DAY, DAY, List.of(SYMBOL), 10, 10);
        var pages = new ArrayList<SyncJobRunner.Page<L2DailyFeatures>>();
        source.stream(inspection, List.of(SYMBOL), 10, 10, pages::add, () -> false, Duration.ofSeconds(30));
        var row = pages.getFirst().rows().getFirst();
        assertEquals(108, row.features().size());
        assertEquals("pyarrow-reference-21.0.0", row.features().get(L2DailyFeatureField.FEATURE_VERSION));
        assertEquals(1.25, row.features().get(L2DailyFeatureField.ORDER_LINK_COVERAGE));
        assertEquals(9L, row.features().get(L2DailyFeatureField.DEAL_RECORDS));
        assertNull(row.features().get(L2DailyFeatureField.MFI_SCORE));
    }

    @Test void falseManifestReceiptWrongDateAndDuplicateKeyFailClosed() throws Exception {
        fixture(false, false, false);
        assertThrows(IOException.class, () -> new L2DailyFeaturesParquetSource(root).inspect(DAY, DAY, List.of(SYMBOL), 10, 10));
        fixture(true, true, false);
        assertThrows(IOException.class, () -> new L2DailyFeaturesParquetSource(root).inspect(DAY, DAY, List.of(SYMBOL), 10, 10));
        fixture(true, false, true);
        assertThrows(IOException.class, () -> new L2DailyFeaturesParquetSource(root).inspect(DAY, DAY, List.of(SYMBOL), 10, 10));
    }

    @Test void budgetsMissingSelectedSymbolsAndCancellationPreventWritablePages() throws Exception {
        fixture(true, false, false);
        var source = new L2DailyFeaturesParquetSource(root);
        assertThrows(IOException.class, () -> source.inspect(DAY, DAY, List.of(SYMBOL), 10, 1));
        assertThrows(IOException.class, () -> source.inspect(DAY, DAY, List.of(SYMBOL, "000002.SZ"), 10, 10));
        var inspection = source.inspect(DAY, DAY, List.of(SYMBOL), 10, 10);
        var pages = new ArrayList<SyncJobRunner.Page<L2DailyFeatures>>();
        assertThrows(CancellationException.class, () -> source.stream(inspection, List.of(SYMBOL), 10, 10,
                pages::add, () -> true, Duration.ofSeconds(30)));
        assertTrue(pages.isEmpty());
    }

    @Test void changedSourceCannotReuseFrozenInspection() throws Exception {
        fixture(true, false, false);
        var source = new L2DailyFeaturesParquetSource(root);
        var inspection = source.inspect(DAY, DAY, List.of(SYMBOL), 10, 10);
        fixture(false, false, false);
        var pages = new ArrayList<SyncJobRunner.Page<L2DailyFeatures>>();
        assertThrows(IOException.class, () -> source.stream(inspection, List.of(SYMBOL), 10, 10,
                pages::add, () -> false, Duration.ofSeconds(30)));
        assertTrue(pages.isEmpty());
    }

    @Test void scalarReaderPreservesNanosecondMicrosecondMillisecondAndUtcSemantics() throws Exception {
        var builder = Types.buildMessage();
        for (var unit : LogicalTypeAnnotation.TimeUnit.values())
            builder.addField(Types.optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.timestampType(false, unit)).named(unit.name()));
        builder.addField(Types.optional(PrimitiveTypeName.INT64)
                .as(LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.MICROS)).named("utc"));
        var schema = builder.named("timestamps");
        var group = new SimpleGroupFactory(schema).newGroup().append("NANOS", -1L).append("MICROS", -1L)
                .append("MILLIS", -1L).append("utc", -1L);
        Path file = root.resolve("times.parquet"); write(file, schema, List.of(group));
        var rows = new ArrayList<ObjectNode>(); NativeL2ParquetReader.read(file, null, 2, () -> {}, rows::add);
        assertEquals("1969-12-31T23:59:59.999999999", rows.getFirst().path("NANOS").asText());
        assertEquals("1969-12-31T23:59:59.999999", rows.getFirst().path("MICROS").asText());
        assertEquals("1969-12-31T23:59:59.999", rows.getFirst().path("MILLIS").asText());
        assertEquals("1969-12-31T23:59:59.999999Z", rows.getFirst().path("utc").asText());
    }

    @Test void scalarReaderRejectsInfinityWrongPhysicalSchemaAndDecodedRowOverflow() throws Exception {
        var schema = Types.buildMessage().optional(PrimitiveTypeName.DOUBLE).named("value").named("numbers");
        Path file = root.resolve("numbers.parquet");
        write(file, schema, List.of(new SimpleGroupFactory(schema).newGroup().append("value", Double.POSITIVE_INFINITY)));
        assertThrows(IOException.class, () -> NativeL2ParquetReader.read(file, null, 1, () -> {}, ignored -> {}));
        write(file, schema, List.of(new SimpleGroupFactory(schema).newGroup(), new SimpleGroupFactory(schema).newGroup()));
        assertThrows(IOException.class, () -> NativeL2ParquetReader.read(file, java.util.Map.of("value", "int64"), 2, () -> {}, ignored -> {}));
        assertThrows(IOException.class, () -> NativeL2ParquetReader.read(file, null, 1, () -> {}, ignored -> {}));
    }

    private void fixture(boolean certified, boolean wrongDate, boolean duplicate) throws Exception {
        Path feature = partition("l2_daily_features").resolve("part-00000.parquet");
        var builder = Types.buildMessage();
        for (var field : L2DailyFeatureField.values()) {
            PrimitiveTypeName primitive = switch (field.storageType()) {
                case TIMESTAMP, LONG -> PrimitiveTypeName.INT64; case STRING, SYMBOL -> PrimitiveTypeName.BINARY;
                case BOOLEAN -> PrimitiveTypeName.BOOLEAN; case DOUBLE -> PrimitiveTypeName.DOUBLE;
                default -> throw new IllegalStateException();
            };
            var value = Types.optional(primitive);
            if (field.storageType() == StorageType.TIMESTAMP) value.as(LogicalTypeAnnotation.timestampType(false, LogicalTypeAnnotation.TimeUnit.NANOS));
            if (field.storageType() == StorageType.STRING || field.storageType() == StorageType.SYMBOL) value.as(LogicalTypeAnnotation.stringType());
            builder.addField(value.named(field.fieldName()));
        }
        MessageType schema = builder.named("daily");
        var row = new SimpleGroupFactory(schema).newGroup();
        row.append("ts", (wrongDate ? DAY.minusDays(1) : DAY).atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L);
        row.append("symbol", SYMBOL);
        for (var field : L2DailyFeatureField.values()) {
            if (field.identity()) continue;
            switch (field.storageType()) {
                case LONG -> row.append(field.fieldName(), 9L);
                case BOOLEAN -> row.append(field.fieldName(), true);
                case DOUBLE -> row.append(field.fieldName(), field == L2DailyFeatureField.MFI_SCORE ? Double.NaN : 1.25);
                case STRING -> row.append(field.fieldName(), "native-test");
                default -> throw new IllegalStateException();
            }
        }
        write(feature, schema, duplicate ? List.of(row, row) : List.of(row));
        var manifestSchema = Types.buildMessage()
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("trade_date")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("symbol")
                .optional(PrimitiveTypeName.BOOLEAN).named("daily_feature_ok")
                .optional(PrimitiveTypeName.INT64).named("batch_id")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("output_paths").named("manifest");
        var receipt = new SimpleGroupFactory(manifestSchema).newGroup().append("trade_date", "20260921")
                .append("symbol", SYMBOL).append("daily_feature_ok", certified).append("batch_id", 0L)
                .append("output_paths", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                        java.util.Map.of("daily_features", feature.toAbsolutePath().toString())));
        write(partition("l2_dataset_manifest").resolve("part-00000.parquet"), manifestSchema, List.of(receipt));
    }

    private Path partition(String dataset) {
        return root.resolve(dataset).resolve("year=2026/month=09/trade_date=20260921");
    }
    private static void write(Path file, MessageType schema, List<Group> rows) throws IOException {
        Files.createDirectories(file.getParent()); Files.deleteIfExists(file);
        try (var writer = ExampleParquetWriter.builder(new LocalOutputFile(file)).withType(schema)
                .withConf(new PlainParquetConfiguration()).withCompressionCodec(CompressionCodecName.SNAPPY).build()) {
            for (var row : rows) writer.write(row);
        }
    }
}
