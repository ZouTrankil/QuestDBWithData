package com.zoutrankil.market.us.marketdata;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** 文件流为两字节网络序长度加 ITCH payload；先读目录再截取明确的完整消息区间。 */
public final class NasdaqItchMapper {
    private NasdaqItchMapper() {}

    public static List<Map<String, Object>> messages(
            InputStream compressed,
            LocalDate date,
            long first,
            int count,
            String sha,
            Instant observed)
            throws Exception {
        if (first < 1 || first > 5_000_000 || count < 1 || count > 100000)
            throw new IllegalArgumentException("ITCH 解析范围须明确且有界");
        var names = new HashMap<Integer, String>();
        var rows = new ArrayList<Map<String, Object>>();
        long offset = 0;
        Instant midnight = date.atStartOfDay(ZoneId.of("America/New_York")).toInstant();
        try (var input = new DataInputStream(new GZIPInputStream(compressed))) {
            for (long index = 1; index < first + count; index++) {
                int size = input.readUnsignedShort();
                if (size < 11 || size > 256) throw new IllegalArgumentException("ITCH 消息长度非法");
                byte[] p = input.readNBytes(size);
                if (p.length != size) throw new EOFException("ITCH 范围最后一条消息不完整");
                String type = Character.toString((char) p[0]);
                int locate = (int) unsigned(p, 1, 2);
                long nanos = unsigned(p, 5, 6);
                if (nanos >= 86_400_000_000_000L)
                    throw new IllegalArgumentException("ITCH 纳秒偏移不属于源文件日期");
                if (type.equals("R")) {
                    length(p, 39);
                    String name = new String(p, 11, 8, StandardCharsets.US_ASCII).trim();
                    if (name.isEmpty() || names.putIfAbsent(locate, name) != null)
                        throw new IllegalArgumentException("ITCH 当日目录有空值或重复定位号");
                }
                if (index >= first) {
                    var r = new LinkedHashMap<String, Object>();
                    r.put("ts", midnight.plusNanos(nanos).toString());
                    r.put("message_index", index);
                    r.put("message_type", type);
                    r.put("stock_locate", locate);
                    r.put("tracking_number", (int) unsigned(p, 3, 2));
                    r.put("symbol", names.get(locate));
                    r.put("order_ref", null);
                    r.put("new_order_ref", null);
                    r.put("side", null);
                    r.put("shares", null);
                    r.put("price_4", null);
                    switch (type) {
                        case "A", "F", "P" -> {
                            length(p, type.equals("A") ? 36 : type.equals("F") ? 40 : 44);
                            r.put("order_ref", unsignedText(p, 11));
                            r.put("side", Character.toString((char) p[19]));
                            r.put("shares", unsigned(p, 20, 4));
                            r.put("price_4", unsigned(p, 32, 4));
                            String stock = new String(p, 24, 8, StandardCharsets.US_ASCII).trim();
                            if (!stock.equals(names.get(locate)))
                                throw new IllegalArgumentException("ITCH 新单代码与当日目录不符");
                        }
                        case "D" -> {
                            length(p, 19);
                            r.put("order_ref", unsignedText(p, 11));
                        }
                        case "E", "C", "X" -> {
                            length(p, type.equals("E") ? 31 : type.equals("C") ? 36 : 23);
                            r.put("order_ref", unsignedText(p, 11));
                            r.put("shares", unsigned(p, 19, 4));
                            if (type.equals("C")) r.put("price_4", unsigned(p, 32, 4));
                        }
                        case "U" -> {
                            length(p, 35);
                            r.put("order_ref", unsignedText(p, 11));
                            r.put("new_order_ref", unsignedText(p, 19));
                            r.put("shares", unsigned(p, 27, 4));
                            r.put("price_4", unsigned(p, 31, 4));
                        }
                        default -> {
                            /* 其他消息保留原字节和公共头，不虚构未实现的业务字段。 */
                        }
                    }
                    if (locate != 0 && r.get("symbol") == null)
                        throw new IllegalArgumentException("ITCH 消息缺少同日证券目录");
                    r.put("raw_hex", HexFormat.of().formatHex(p));
                    r.put("decompressed_offset", offset);
                    r.put("source_sha256", sha);
                    r.put("source_complete", false);
                    r.put("observed_at", observed.toString());
                    rows.add(r);
                }
                offset += 2L + size;
            }
        }
        return rows;
    }

    private static void length(byte[] p, int expected) {
        if (p.length != expected) throw new IllegalArgumentException("ITCH 类型与消息长度不符");
    }

    private static long unsigned(byte[] p, int offset, int size) {
        if (offset + size > p.length) throw new IllegalArgumentException("ITCH 字段越界");
        long value = 0;
        for (int i = offset; i < offset + size; i++) value = (value << 8) | (p[i] & 255L);
        return value;
    }

    private static String unsignedText(byte[] p, int offset) {
        return Long.toUnsignedString(unsigned(p, offset, 8));
    }
}
