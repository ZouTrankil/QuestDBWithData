package com.zoutrankil.data.l2live.storage;

import com.zoutrankil.data.l2live.domain.AliyunL2WireParser;
import com.zoutrankil.data.l2live.domain.L2CleanEvent;
import com.zoutrankil.data.l2live.domain.L2MinuteAccumulator;
import com.zoutrankil.data.l2live.domain.L2MinuteState;
import com.zoutrankil.data.l2live.domain.RawCapture;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 非 JSON 的内容标识；整数宽度及十进制尾零不改变业务值身份。 */
final class LiveValueIdentity {
    private LiveValueIdentity() {}

    static String hash(Object value) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                write(out, value, 0);
            }
            if (bytes.size() > 8 * 1024 * 1024)
                throw new IllegalArgumentException(
                        "Prepared live batch exceeds frozen-byte budget");
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String batch(RawJournal.FrozenData data) {
        return hash(List.of(data.events(), data.states(), data.accumulatorState()));
    }

    private static Map<String, Object> fields(
            Map<String, Object> values, Map<String, String> types, boolean minute) {
        if (!types.keySet().containsAll(values.keySet()))
            throw new IllegalArgumentException("Unregistered live Parquet field");
        var result = new TreeMap<String, Object>();
        types.forEach(
                (k, type) -> {
                    Object v = values.get(k);
                    if (v != null && type.equals("DOUBLE"))
                        v =
                                LiveParquetCodec.decimal(k, type, minute)
                                        ? LiveParquetCodec.exactDecimal(v)
                                        : LiveParquetCodec.floating(v);
                    result.put(k, v);
                });
        return result;
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void write(DataOutputStream out, Object value, int depth) throws IOException {
        if (depth > 32 || out.size() > 8 * 1024 * 1024)
            throw new IllegalArgumentException("Live checkpoint exceeds its bounds");
        if (value == null) {
            out.writeByte(0);
        } else if (value instanceof String s) {
            out.writeByte(1);
            text(out, s);
        } else if (value instanceof Boolean b) {
            out.writeByte(2);
            out.writeBoolean(b);
        } else if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            out.writeByte(3);
            out.writeLong(((Number) value).longValue());
        } else if (value instanceof BigDecimal n) {
            out.writeByte(4);
            text(out, n.stripTrailingZeros().toPlainString());
        } else if (value instanceof Double n) {
            if (!Double.isFinite(n)) throw new IllegalArgumentException("Nonfinite live value");
            out.writeByte(5);
            out.writeDouble(n);
        } else if (value instanceof byte[] b) {
            out.writeByte(6);
            out.writeInt(b.length);
            out.write(b);
        } else if (value instanceof Instant t) {
            out.writeByte(7);
            out.writeLong(t.getEpochSecond());
            out.writeInt(t.getNano());
        } else if (value instanceof LocalDate d) {
            out.writeByte(8);
            out.writeLong(d.toEpochDay());
        } else if (value instanceof List<?> list) {
            out.writeByte(9);
            out.writeInt(list.size());
            for (var item : list) write(out, item, depth + 1);
        } else if (value instanceof Map<?, ?> map) {
            out.writeByte(10);
            out.writeInt(map.size());
            var sorted = new TreeMap<String, Object>();
            map.forEach(
                    (k, v) -> {
                        if (!(k instanceof String s))
                            throw new IllegalArgumentException("String checkpoint key required");
                        sorted.put(s, v);
                    });
            for (var entry : sorted.entrySet()) {
                text(out, entry.getKey());
                write(out, entry.getValue(), depth + 1);
            }
        } else if (value instanceof RawCapture r) {
            write(
                    out,
                    List.of(
                            r.captureId(),
                            r.sequence(),
                            r.sessionId(),
                            r.topic(),
                            r.payloadBytes(),
                            r.recvTs(),
                            r.captureDate(),
                            r.qos(),
                            r.retained(),
                            r.duplicate(),
                            r.subscriptionAuthorized()),
                    depth + 1);
        } else if (value instanceof L2CleanEvent e) {
            write(
                    out,
                    Arrays.asList(
                            e.capture(),
                            e.symbol(),
                            e.exchange(),
                            e.channel(),
                            e.tradeDate(),
                            e.exchangeTs(),
                            e.parseStatus(),
                            e.parseError(),
                            fields(e.fields(), AliyunL2WireParser.FIELD_TYPES, false)),
                    depth + 1);
        } else if (value instanceof L2MinuteState m) {
            write(
                    out,
                    List.of(
                            m.symbol(),
                            m.tradeDate(),
                            m.minuteStart(),
                            m.availableAt(),
                            m.revision(),
                            fields(m.metrics(), L2MinuteAccumulator.METRIC_TYPES, true)),
                    depth + 1);
        } else
            throw new IllegalArgumentException(
                    "Unsupported live value: " + value.getClass().getSimpleName());
    }
}
