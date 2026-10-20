package com.zoutrankil.market.btc.marketdata;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipInputStream;

/** Binance 单归档先验 SHA，再解压；只接受明确的毫秒/微秒与完整连续小时。 */
public final class CryptoPriceMapper {
    private CryptoPriceMapper() {}

    public static List<Map<String, Object>> binance(
            byte[] zip,
            String symbol,
            boolean perpetual,
            String sha,
            Instant observed,
            LocalDate from,
            LocalDate through)
            throws Exception {
        return binance(zip, symbol, perpetual, sha, observed, from, through, 60, List.of());
    }

    public static List<Map<String, Object>> binance(
            byte[] zip,
            String symbol,
            boolean perpetual,
            String sha,
            Instant observed,
            LocalDate from,
            LocalDate through,
            int minutes,
            List<CryptoTradingClosure> closures)
            throws Exception {
        byte[] csv;
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            var entry = input.getNextEntry();
            if (entry == null || !entry.getName().endsWith(".csv"))
                throw new IllegalArgumentException("Binance 归档不是单 CSV");
            csv = input.readNBytes(8 * 1024 * 1024 + 1);
            if (csv.length > 8 * 1024 * 1024 || input.getNextEntry() != null)
                throw new IllegalArgumentException("Binance 归档超过有界 CSV 范围");
        }
        var rows = new ArrayList<Map<String, Object>>();
        for (String line : new String(csv, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank() || line.startsWith("open_time,")) continue;
            String[] c = line.split(",", -1);
            if (c.length < 11) throw new IllegalArgumentException("Binance OHLCV 字段不足");
            long epoch = Long.parseLong(c[0]);
            boolean micros = epoch >= 100_000_000_000_000L;
            Instant ts =
                    micros
                            ? Instant.ofEpochSecond(epoch / 1_000_000, (epoch % 1_000_000) * 1000)
                            : Instant.ofEpochMilli(epoch);
            boolean expectedMicros =
                    !perpetual && !ts.isBefore(Instant.parse("2025-01-01T00:00:00Z"));
            if (micros != expectedMicros)
                throw new IllegalArgumentException("Binance 时间单位与现货/合约日期不符");
            rows.add(
                    row(
                            ts,
                            "BINANCE",
                            symbol,
                            perpetual ? "USDT_PERPETUAL" : "SPOT",
                            minutes,
                            Arrays.asList(c[1], c[2], c[3], c[4], c[5], c[7]),
                            sha,
                            observed));
        }
        if (minutes == 60 && !closures.isEmpty()) {
            for (var missing : missingHours(rows, from, through))
                if (closures.stream()
                        .noneMatch(
                                c ->
                                        c.containsHour(
                                                "BINANCE",
                                                perpetual ? "USDT_PERPETUAL" : "SPOT",
                                                missing)))
                    throw new IllegalArgumentException("缺失小时没有对应的官方停市窗口");
        } else requireContinuous(rows, from, through, minutes);
        return rows;
    }

    public static List<Instant> missingHours(
            List<Map<String, Object>> rows, LocalDate from, LocalDate through) {
        var actual = new HashSet<Instant>();
        Instant first = from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                until = through.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        if (!until.isAfter(first)) throw new IllegalArgumentException("小时窗口倒置");
        for (var row : rows) {
            var ts = Instant.parse(row.get("ts").toString());
            if (((Number) row.get("interval_minutes")).intValue() != 60
                    || ts.isBefore(first)
                    || !ts.isBefore(until)
                    || ts.getNano() != 0
                    || ts.getEpochSecond() % 3600 != 0
                    || !actual.add(ts)) throw new IllegalArgumentException("小时源越界、重复或不在整小时");
        }
        var missing = new ArrayList<Instant>();
        for (var time = first; time.isBefore(until); time = time.plusSeconds(3600))
            if (!actual.contains(time)) missing.add(time);
        return missing;
    }

    public static void reconcileNativeDaily(
            List<Map<String, Object>> hourly, List<Map<String, Object>> nativeDaily) {
        var grouped = new HashMap<LocalDate, List<Map<String, Object>>>();
        for (var row : hourly)
            grouped.computeIfAbsent(
                            Instant.parse(row.get("ts").toString())
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate(),
                            k -> new ArrayList<>())
                    .add(row);
        if (grouped.size() != nativeDaily.size())
            throw new IllegalArgumentException("原生日线与实际小时覆盖日期不同");
        for (var day : nativeDaily) {
            if (((Number) day.get("interval_minutes")).intValue() != 1440)
                throw new IllegalArgumentException("对照来源不是 UTC 日线");
            var date = Instant.parse(day.get("ts").toString()).atZone(ZoneOffset.UTC).toLocalDate();
            var rows =
                    grouped.getOrDefault(date, List.of()).stream()
                            .sorted(Comparator.comparing(r -> r.get("ts").toString()))
                            .toList();
            if (rows.isEmpty()) throw new IllegalArgumentException("原生日线没有可核对的小时记录");
            for (var row : rows)
                for (String key : List.of("exchange", "symbol", "instrument"))
                    if (!Objects.equals(row.get(key), day.get(key)))
                        throw new IllegalArgumentException("日线对照不能混合交易所或产品");
            var derived = new LinkedHashMap<String, Object>();
            derived.put("open", rows.getFirst().get("open"));
            derived.put("close", rows.getLast().get("close"));
            derived.put(
                    "high",
                    rows.stream()
                            .mapToDouble(r -> ((Number) r.get("high")).doubleValue())
                            .max()
                            .orElseThrow());
            derived.put(
                    "low",
                    rows.stream()
                            .mapToDouble(r -> ((Number) r.get("low")).doubleValue())
                            .min()
                            .orElseThrow());
            for (String field : List.of("volume", "quote_volume"))
                derived.put(
                        field,
                        rows.stream()
                                .mapToDouble(r -> ((Number) r.get(field)).doubleValue())
                                .sum());
            for (String field : List.of("open", "high", "low", "close", "volume", "quote_volume")) {
                double actual = ((Number) derived.get(field)).doubleValue(),
                        expected = ((Number) day.get(field)).doubleValue();
                if (Math.abs(actual - expected) > Math.max(1e-8, Math.abs(expected) * 1e-12))
                    throw new IllegalArgumentException("缺口月份的原生日线与实际小时成交不符：" + date + " " + field);
            }
        }
    }

    public static Map<String, Object> row(
            Instant ts,
            String exchange,
            String symbol,
            String instrument,
            int minutes,
            List<String> numbers,
            String sha,
            Instant observed) {
        if (!Set.of(60, 1440).contains(minutes)
                || ts.getNano() != 0
                || ts.getEpochSecond() % (minutes * 60L) != 0
                || !Set.of("BTCUSDT", "ETHUSDT").contains(symbol)
                || !Set.of("BINANCE", "BYBIT").contains(exchange)
                || !Set.of("SPOT", "USDT_PERPETUAL").contains(instrument)
                || numbers.size() != 6) throw new IllegalArgumentException("币对、时间边界或频率非法");
        var r = new LinkedHashMap<String, Object>();
        r.put("ts", ts.toString());
        r.put("exchange", exchange);
        r.put("symbol", symbol);
        r.put("instrument", instrument);
        r.put("interval_minutes", minutes);
        String[] fields = {"open", "high", "low", "close", "volume", "quote_volume"};
        for (int i = 0; i < fields.length; i++) {
            double value = Double.parseDouble(numbers.get(i));
            if (!Double.isFinite(value) || value < 0 || (i < 4 && value == 0))
                throw new IllegalArgumentException("币价/成交量非法");
            r.put(fields[i], value);
        }
        if ((double) r.get("high") < Math.max((double) r.get("open"), (double) r.get("close"))
                || (double) r.get("low")
                        > Math.min((double) r.get("open"), (double) r.get("close")))
            throw new IllegalArgumentException("币价 OHLC 关系非法");
        r.put("source_sha256", sha);
        r.put("observed_at", observed.toString());
        return r;
    }

    public static void requireContinuous(
            List<Map<String, Object>> rows, LocalDate from, LocalDate through, int minutes) {
        var sorted =
                rows.stream().map(r -> Instant.parse(r.get("ts").toString())).sorted().toList();
        long start = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                end = through.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        if (end <= start || sorted.size() != (end - start) / (minutes * 60L))
            throw new IllegalArgumentException("加密来源区间有缺口或多余数据");
        for (int i = 0; i < sorted.size(); i++)
            if (sorted.get(i).getEpochSecond() != start + i * minutes * 60L)
                throw new IllegalArgumentException("加密来源缺小时或重复业务键");
    }

    public static List<Map<String, Object>> daily(List<Map<String, Object>> hourly) {
        var days = new TreeMap<LocalDate, List<Map<String, Object>>>();
        for (var row : hourly)
            days.computeIfAbsent(
                            Instant.parse(row.get("ts").toString())
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate(),
                            k -> new ArrayList<>())
                    .add(row);
        var output = new ArrayList<Map<String, Object>>();
        for (var entry : days.entrySet()) {
            var rows =
                    entry.getValue().stream()
                            .sorted(Comparator.comparing(r -> r.get("ts").toString()))
                            .toList();
            requireContinuous(rows, entry.getKey(), entry.getKey(), 60);
            var first = rows.getFirst();
            for (var r : rows)
                for (String key :
                        List.of("exchange", "symbol", "instrument", "source_sha256", "observed_at"))
                    if (!Objects.equals(r.get(key), first.get(key)))
                        throw new IllegalArgumentException("不能把不同来源或币对合成日线");
            var r = new LinkedHashMap<>(first);
            r.put("ts", entry.getKey().atStartOfDay(ZoneOffset.UTC).toInstant().toString());
            r.put("interval_minutes", 1440);
            r.put(
                    "high",
                    rows.stream()
                            .mapToDouble(x -> ((Number) x.get("high")).doubleValue())
                            .max()
                            .orElseThrow());
            r.put(
                    "low",
                    rows.stream()
                            .mapToDouble(x -> ((Number) x.get("low")).doubleValue())
                            .min()
                            .orElseThrow());
            r.put("close", rows.getLast().get("close"));
            for (String name : List.of("volume", "quote_volume"))
                r.put(
                        name,
                        rows.stream().mapToDouble(x -> ((Number) x.get(name)).doubleValue()).sum());
            output.add(r);
        }
        return output;
    }
}
