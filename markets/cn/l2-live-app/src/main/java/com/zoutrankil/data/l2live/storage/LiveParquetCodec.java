package com.zoutrankil.data.l2live.storage;

import com.zoutrankil.data.l2live.domain.AliyunL2WireParser;
import com.zoutrankil.data.l2live.domain.L2CleanEvent;
import com.zoutrankil.data.l2live.domain.L2MinuteAccumulator;
import com.zoutrankil.data.l2live.domain.L2MinuteState;
import com.zoutrankil.data.l2live.domain.RawCapture;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/** 标准 Parquet 列：原始二进制、UTC 纳秒时间、DATE、精确 DECIMAL、整数与布尔值。 */
public final class LiveParquetCodec {
    private LiveParquetCodec() {}

    private static final String RAW_FIELDS =
            "required binary capture_id (STRING); required int64 sequence; required binary session_id (STRING); required binary topic (STRING); required binary payload; required binary payload_sha256 (STRING); required int64 recv_ts (TIMESTAMP(NANOS,true)); required int32 capture_date (DATE); required int32 qos; required boolean retained; required boolean duplicate; required boolean subscription_authorized;";
    static final MessageType RAW = schema("l2_raw", RAW_FIELDS);
    static final MessageType EVENTS =
            schema(
                    "l2_events",
                    "required binary capture_id (STRING); required binary symbol (STRING); required binary exchange (STRING); required binary channel (STRING); required int32 trade_date (DATE); optional int64 exchange_ts (TIMESTAMP(NANOS,true)); required binary parse_status (STRING); required binary parse_error (STRING);"
                            + fields(AliyunL2WireParser.FIELD_TYPES, false));
    static final MessageType MINUTES =
            schema(
                    "l2_minutes",
                    "required binary symbol (STRING); required int32 trade_date (DATE); required int64 minute_start (TIMESTAMP(NANOS,true)); required int64 available_at (TIMESTAMP(NANOS,true)); required int64 revision;"
                            + fields(L2MinuteAccumulator.METRIC_TYPES, true));
    static final MessageType CHECKPOINT =
            schema(
                    "l2_checkpoint",
                    "required group path (LIST) { repeated group list { required binary element (STRING); } } required binary kind (STRING); optional binary text_value (STRING); optional int64 long_value; optional double double_value; optional boolean boolean_value;");

    private static MessageType schema(String name, String fields) {
        return MessageTypeParser.parseMessageType("message " + name + " { " + fields + " }");
    }

    private static String fields(Map<String, String> types, boolean minute) {
        var result = new StringBuilder();
        types.forEach(
                (name, type) ->
                        result.append("optional ")
                                .append(physical(name, type, minute))
                                .append(' ')
                                .append(name)
                                .append(annotation(name, type, minute))
                                .append(';'));
        return result.toString();
    }

    static boolean decimal(String name, String type, boolean minute) {
        if (!type.equals("DOUBLE")) return false;
        if (!minute) return !name.equals("obi_top1");
        return name.matches("(?:bid|ask)_price_[0-9]+")
                || Set.of(
                                "last_price",
                                "mid_price",
                                "open_1m",
                                "high_1m",
                                "low_1m",
                                "close_1m",
                                "amount_1m",
                                "active_buy_amount_1m",
                                "active_sell_amount_1m",
                                "large_buy_amount_1m",
                                "large_sell_amount_1m",
                                "near_bid_add_amount",
                                "near_ask_add_amount",
                                "near_bid_cancel_amount",
                                "near_ask_cancel_amount")
                        .contains(name);
    }

    private static String physical(String name, String type, boolean minute) {
        if (decimal(name, type, minute)) return "fixed_len_byte_array(16)";
        if (name.equals("book_snapshot_ts")) return "int64";
        return switch (type) {
            case "STRING" -> "binary";
            case "LONG" -> "int64";
            case "INT" -> "int32";
            case "BOOLEAN" -> "boolean";
            case "DOUBLE" -> "double";
            default -> throw new IllegalArgumentException(type);
        };
    }

    private static String annotation(String name, String type, boolean minute) {
        if (decimal(name, type, minute))
            return " (DECIMAL(38," + (name.equals("mid_price") ? 5 : 4) + "))";
        if (name.equals("book_snapshot_ts")) return " (TIMESTAMP(NANOS,true))";
        return type.equals("STRING") ? " (STRING)" : "";
    }

    static ParquetWriter<Group> writer(Path path, MessageType schema, String kind, String id)
            throws Exception {
        return ExampleParquetWriter.builder(new LocalOutputFile(path))
                .withConf(new Configuration(false))
                .withType(schema)
                .withCompressionCodec(CompressionCodecName.GZIP)
                .withRowGroupSize(1024 * 1024)
                .withPageSize(16 * 1024)
                .withDictionaryPageSize(16 * 1024)
                .withExtraMetaData(
                        Map.of("l2.format.version", "1", "l2.dataset", kind, "l2.batch.id", id))
                .build();
    }

    static void read(
            Path path,
            MessageType schema,
            String kind,
            String id,
            long limit,
            Consumer<Group> accept)
            throws Exception {
        try (var reader = ParquetFileReader.open(new LocalInputFile(path))) {
            var footer = reader.getFooter();
            var metadata = footer.getFileMetaData();
            var card = metadata.getKeyValueMetaData();
            if (!metadata.getSchema().equals(schema)
                    || !"1".equals(card.get("l2.format.version"))
                    || !kind.equals(card.get("l2.dataset"))
                    || id != null && !id.equals(card.get("l2.batch.id"))
                    || reader.getRecordCount() > limit)
                throw new IllegalStateException(
                        "Parquet schema, version, batch or row bound differs");
            var column = new ColumnIOFactory().getColumnIO(schema);
            for (var pages = reader.readNextRowGroup();
                    pages != null;
                    pages = reader.readNextRowGroup()) {
                var page = pages;
                try (page) {
                    var records = column.getRecordReader(pages, new GroupRecordConverter(schema));
                    for (long i = 0; i < pages.getRowCount(); i++) accept.accept(records.read());
                }
            }
        }
    }

    static Group raw(RawCapture r) {
        var g = new SimpleGroupFactory(RAW).newGroup();
        g.add("capture_id", r.captureId());
        g.add("sequence", r.sequence());
        g.add("session_id", r.sessionId());
        g.add("topic", r.topic());
        g.add("payload", Binary.fromConstantByteArray(r.payloadBytes()));
        g.add("payload_sha256", LiveStorageJson.sha256(r.payloadBytes()));
        g.add("recv_ts", nanos(r.recvTs()));
        g.add("capture_date", Math.toIntExact(r.captureDate().toEpochDay()));
        g.add("qos", r.qos());
        g.add("retained", r.retained());
        g.add("duplicate", r.duplicate());
        g.add("subscription_authorized", r.subscriptionAuthorized());
        return g;
    }

    private static RawCapture raw(Group g) {
        var bytes = g.getBinary("payload", 0).getBytes();
        if (!LiveStorageJson.sha256(bytes).equals(g.getString("payload_sha256", 0)))
            throw new IllegalStateException("Parquet raw payload hash differs");
        return new RawCapture(
                g.getString("capture_id", 0),
                g.getLong("sequence", 0),
                g.getString("session_id", 0),
                g.getString("topic", 0),
                Base64.getEncoder().encodeToString(bytes),
                instant(g.getLong("recv_ts", 0)),
                LocalDate.ofEpochDay(g.getInteger("capture_date", 0)),
                g.getInteger("qos", 0),
                g.getBoolean("retained", 0),
                g.getBoolean("duplicate", 0),
                g.getBoolean("subscription_authorized", 0));
    }

    public static List<RawCapture> readRaw(Path path) throws Exception {
        var result = new ArrayList<RawCapture>();
        read(path, RAW, "raw", null, RawJournal.MAX_BATCH_ROWS, g -> result.add(raw(g)));
        return List.copyOf(result);
    }

    static void validateRawExport(Path path, long count, long lastSequence) throws Exception {
        long[] observed = {0, 0};
        read(
                path,
                RAW,
                "raw",
                "export",
                Long.MAX_VALUE,
                g -> {
                    var r = raw(g);
                    if (r.sequence() <= observed[1])
                        throw new IllegalStateException("Raw Parquet export order differs");
                    observed[0]++;
                    observed[1] = r.sequence();
                });
        if (observed[0] != count || observed[1] != lastSequence)
            throw new IllegalStateException("Raw Parquet export coverage differs");
    }

    static void writeBatch(Path directory, String id, RawJournal.FrozenData data) throws Exception {
        try (var w = writer(directory.resolve("raw.parquet"), RAW, "raw", id)) {
            for (var e : data.events()) w.write(raw(e.capture()));
        }
        try (var w = writer(directory.resolve("events.parquet"), EVENTS, "events", id)) {
            for (var e : data.events()) {
                var g = new SimpleGroupFactory(EVENTS).newGroup();
                g.add("capture_id", e.capture().captureId());
                g.add("symbol", e.symbol());
                g.add("exchange", e.exchange());
                g.add("channel", e.channel());
                g.add("trade_date", Math.toIntExact(e.tradeDate().toEpochDay()));
                if (e.exchangeTs() != null) g.add("exchange_ts", nanos(e.exchangeTs()));
                g.add("parse_status", e.parseStatus());
                g.add("parse_error", e.parseError());
                writeFields(g, e.fields(), AliyunL2WireParser.FIELD_TYPES, false);
                w.write(g);
            }
        }
        try (var w = writer(directory.resolve("minutes.parquet"), MINUTES, "minutes", id)) {
            for (var m : data.states()) {
                var g = new SimpleGroupFactory(MINUTES).newGroup();
                g.add("symbol", m.symbol());
                g.add("trade_date", Math.toIntExact(m.tradeDate().toEpochDay()));
                g.add("minute_start", nanos(m.minuteStart()));
                g.add("available_at", nanos(m.availableAt()));
                g.add("revision", m.revision());
                writeFields(g, m.metrics(), L2MinuteAccumulator.METRIC_TYPES, true);
                w.write(g);
            }
        }
        try (var w =
                writer(directory.resolve("checkpoint.parquet"), CHECKPOINT, "checkpoint", id)) {
            writeNode(w, List.of(), data.accumulatorState(), 0, new int[1]);
        }
    }

    static RawJournal.FrozenData readBatch(Path directory, String id) throws Exception {
        var captures = new LinkedHashMap<String, RawCapture>();
        read(
                directory.resolve("raw.parquet"),
                RAW,
                "raw",
                id,
                RawJournal.MAX_BATCH_ROWS,
                g -> {
                    var r = raw(g);
                    if (captures.put(r.captureId(), r) != null)
                        throw new IllegalStateException("Duplicate Parquet capture identity");
                });
        var events = new ArrayList<L2CleanEvent>();
        var used = new HashSet<String>();
        read(
                directory.resolve("events.parquet"),
                EVENTS,
                "events",
                id,
                RawJournal.MAX_BATCH_ROWS,
                g -> {
                    String capture = g.getString("capture_id", 0);
                    var raw = captures.get(capture);
                    if (raw == null || !used.add(capture))
                        throw new IllegalStateException("Parquet event/raw identity differs");
                    events.add(
                            new L2CleanEvent(
                                    raw,
                                    g.getString("symbol", 0),
                                    g.getString("exchange", 0),
                                    g.getString("channel", 0),
                                    LocalDate.ofEpochDay(g.getInteger("trade_date", 0)),
                                    g.getFieldRepetitionCount("exchange_ts") == 0
                                            ? null
                                            : instant(g.getLong("exchange_ts", 0)),
                                    g.getString("parse_status", 0),
                                    g.getString("parse_error", 0),
                                    readFields(g, AliyunL2WireParser.FIELD_TYPES, false)));
                });
        if (events.isEmpty() || used.size() != captures.size())
            throw new IllegalStateException("Parquet batch capture closure differs");
        var states = new ArrayList<L2MinuteState>();
        read(
                directory.resolve("minutes.parquet"),
                MINUTES,
                "minutes",
                id,
                RawJournal.MAX_BATCH_ROWS,
                g ->
                        states.add(
                                new L2MinuteState(
                                        g.getString("symbol", 0),
                                        LocalDate.ofEpochDay(g.getInteger("trade_date", 0)),
                                        instant(g.getLong("minute_start", 0)),
                                        instant(g.getLong("available_at", 0)),
                                        g.getLong("revision", 0),
                                        readFields(g, L2MinuteAccumulator.METRIC_TYPES, true))));
        return new RawJournal.FrozenData(
                List.copyOf(events),
                List.copyOf(states),
                readCheckpoint(directory.resolve("checkpoint.parquet"), id));
    }

    private static void writeFields(
            Group g, Map<String, Object> values, Map<String, String> types, boolean minute) {
        if (!types.keySet().containsAll(values.keySet()))
            throw new IllegalArgumentException("Unregistered live Parquet columns");
        types.forEach(
                (name, type) -> {
                    Object v = values.get(name);
                    if (v == null) return;
                    if (decimal(name, type, minute)) {
                        BigDecimal d = exactDecimal(v);
                        d = d.setScale(name.equals("mid_price") ? 5 : 4, RoundingMode.UNNECESSARY);
                        if (d.precision() > 38)
                            throw new IllegalArgumentException(
                                    "Parquet decimal precision exceeded");
                        byte[] source = d.unscaledValue().toByteArray(), fixed = new byte[16];
                        Arrays.fill(fixed, (byte) (d.signum() < 0 ? 0xff : 0));
                        System.arraycopy(source, 0, fixed, 16 - source.length, source.length);
                        g.add(name, Binary.fromConstantByteArray(fixed));
                    } else if (name.equals("book_snapshot_ts"))
                        g.add(name, nanos(Instant.parse((String) v)));
                    else
                        switch (type) {
                            case "STRING" -> g.add(name, (String) v);
                            case "LONG" -> g.add(name, integral(v));
                            case "INT" -> g.add(name, Math.toIntExact(integral(v)));
                            case "BOOLEAN" -> g.add(name, (Boolean) v);
                            case "DOUBLE" -> {
                                double d = floating(v);
                                g.add(name, d);
                            }
                            default -> throw new IllegalArgumentException(type);
                        }
                });
    }

    private static Map<String, Object> readFields(
            Group g, Map<String, String> types, boolean minute) {
        var result = new LinkedHashMap<String, Object>();
        types.forEach(
                (name, type) -> {
                    Object v = null;
                    if (g.getFieldRepetitionCount(name) > 0) {
                        if (decimal(name, type, minute))
                            v =
                                    new BigDecimal(
                                            new BigInteger(g.getBinary(name, 0).getBytes()),
                                            name.equals("mid_price") ? 5 : 4);
                        else if (name.equals("book_snapshot_ts"))
                            v = instant(g.getLong(name, 0)).toString();
                        else
                            v =
                                    switch (type) {
                                        case "STRING" -> g.getString(name, 0);
                                        case "LONG" -> g.getLong(name, 0);
                                        case "INT" -> g.getInteger(name, 0);
                                        case "BOOLEAN" -> g.getBoolean(name, 0);
                                        case "DOUBLE" -> g.getDouble(name, 0);
                                        default -> throw new IllegalArgumentException(type);
                                    };
                    }
                    result.put(name, v);
                });
        return result;
    }

    static BigDecimal exactDecimal(Object value) {
        if (value instanceof BigDecimal d) return d;
        if (value instanceof Long n) return BigDecimal.valueOf(n);
        if (value instanceof Integer n) return BigDecimal.valueOf(n.longValue());
        throw new IllegalArgumentException("Exact decimal or integral amount required");
    }

    static double floating(Object value) {
        // schema 2 的通用 JSON 解码会把原 DOUBLE 指标读取为 BigDecimal。
        double result;
        if (value instanceof Double d) result = d;
        else if (value instanceof BigDecimal d) result = d.doubleValue();
        else throw new IllegalArgumentException("Typed floating-point metric required");
        if (!Double.isFinite(result))
            throw new IllegalArgumentException("Nonfinite floating-point metric");
        if (value instanceof BigDecimal d && BigDecimal.valueOf(result).compareTo(d) != 0)
            throw new IllegalArgumentException(
                    "Legacy floating metric cannot round-trip without loss");
        return result;
    }

    private static long integral(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Integer n) return n.longValue();
        throw new IllegalArgumentException("Exact integer required");
    }

    private static long nanos(Instant t) {
        return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000_000L), t.getNano());
    }

    private static Instant instant(long n) {
        return Instant.ofEpochSecond(
                Math.floorDiv(n, 1_000_000_000L), Math.floorMod(n, 1_000_000_000L));
    }

    private static void writeNode(
            ParquetWriter<Group> writer, List<String> path, Object value, int depth, int[] count)
            throws Exception {
        if (depth > 32 || ++count[0] > 300_000)
            throw new IllegalArgumentException("Checkpoint node bounds exceeded");
        var g = new SimpleGroupFactory(CHECKPOINT).newGroup();
        var p = g.addGroup("path");
        for (String segment : path) p.addGroup("list").add("element", segment);
        String kind;
        if (value == null) kind = "null";
        else if (value instanceof Map<?, ?>) kind = "map";
        else if (value instanceof List<?>) kind = "list";
        else if (value instanceof String s) {
            kind = "string";
            g.add("text_value", s);
        } else if (value instanceof Boolean b) {
            kind = "boolean";
            g.add("boolean_value", b);
        } else if (value instanceof Long || value instanceof Integer) {
            kind = "long";
            g.add("long_value", integral(value));
        } else if (value instanceof BigDecimal d) {
            kind = "decimal";
            g.add("text_value", d.toPlainString());
        } else if (value instanceof Double d && Double.isFinite(d)) {
            kind = "double";
            g.add("double_value", d);
        } else throw new IllegalArgumentException("Unsupported checkpoint scalar");
        g.add("kind", kind);
        writer.write(g);
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach(
                    (k, v) -> {
                        if (!(k instanceof String s))
                            throw new IllegalArgumentException("String checkpoint key required");
                        sorted.put(s, v);
                    });
            for (var e : sorted.entrySet()) {
                var child = new ArrayList<>(path);
                child.add(e.getKey());
                writeNode(writer, child, e.getValue(), depth + 1, count);
            }
        } else if (value instanceof List<?> list)
            for (int i = 0; i < list.size(); i++) {
                var child = new ArrayList<>(path);
                child.add(Integer.toString(i));
                writeNode(writer, child, list.get(i), depth + 1, count);
            }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readCheckpoint(Path file, String id) throws Exception {
        var nodes = new HashMap<List<String>, Object>();
        read(
                file,
                CHECKPOINT,
                "checkpoint",
                id,
                300_000,
                g -> {
                    var p = g.getGroup("path", 0);
                    var path = new ArrayList<String>();
                    for (int i = 0; i < p.getFieldRepetitionCount("list"); i++)
                        path.add(p.getGroup("list", i).getString("element", 0));
                    if (path.size() > 32 || nodes.containsKey(path))
                        throw new IllegalStateException("Duplicate or oversized checkpoint path");
                    Object value =
                            switch (g.getString("kind", 0)) {
                                case "map" -> new LinkedHashMap<String, Object>();
                                case "list" -> new ArrayList<>();
                                case "null" -> null;
                                case "string" -> g.getString("text_value", 0);
                                case "boolean" -> g.getBoolean("boolean_value", 0);
                                case "long" -> g.getLong("long_value", 0);
                                case "decimal" -> new BigDecimal(g.getString("text_value", 0));
                                case "double" -> g.getDouble("double_value", 0);
                                default ->
                                        throw new IllegalStateException(
                                                "Unknown checkpoint node type");
                            };
                    if (path.isEmpty()) {
                        if (!(value instanceof Map))
                            throw new IllegalStateException("Checkpoint root must be a map");
                    } else {
                        Object parent = nodes.get(path.subList(0, path.size() - 1));
                        String key = path.getLast();
                        if (parent instanceof Map map) map.put(key, value);
                        else if (parent instanceof List list) {
                            if (!key.equals(Integer.toString(list.size())))
                                throw new IllegalStateException("Checkpoint list order differs");
                            list.add(value);
                        } else throw new IllegalStateException("Checkpoint parent absent");
                    }
                    nodes.put(List.copyOf(path), value);
                });
        if (!(nodes.get(List.of()) instanceof Map<?, ?> root))
            throw new IllegalStateException("Checkpoint root absent");
        return (Map<String, Object>) root;
    }
}
