package com.zoutrankil.market.btc.archive;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

/** 字段按来源含义转换；资金费率不凑固定频率，标记和指数占位列不当成交量。 */
public final class BinanceArchiveMapper {
    private BinanceArchiveMapper() {}

    private static final String HEADER =
            "open_time,open,high,low,close,volume,close_time,quote_volume,count,taker_buy_volume,taker_buy_quote_volume,ignore";

    public static List<Map<String, Object>> parse(
            BinanceArchiveDataset dataset,
            byte[] zip,
            LocalDate from,
            LocalDate through,
            String sha,
            Instant observed)
            throws Exception {
        return parse(dataset, zip, from, through, sha, observed, false);
    }

    public record Coverage(
            List<Map<String, Object>> rows,
            List<String> missingOpenTimes,
            List<Map<String, String>> shortenedBars,
            List<Map<String, String>> excludedRows) {
        public boolean complete() {
            return missingOpenTimes.isEmpty() && shortenedBars.isEmpty() && excludedRows.isEmpty();
        }
    }

    public static Coverage inspect(
            BinanceArchiveDataset dataset,
            byte[] zip,
            LocalDate from,
            LocalDate through,
            String sha,
            Instant observed)
            throws Exception {
        var rows = parse(dataset, zip, from, through, sha, observed, true);
        var missing = new ArrayList<String>();
        var shortened = new ArrayList<Map<String, String>>();
        var excluded = new ArrayList<Map<String, String>>();
        if (dataset != BinanceArchiveDataset.FUNDING) {
            var times = new HashSet<String>();
            long step = dataset.interval().equals("1d") ? 86400L : 3600L;
            boolean micros = dataset.spot() && !from.isBefore(LocalDate.of(2025, 1, 1));
            for (var row : rows) {
                String open = row.get("open_time").toString();
                times.add(open);
                Instant expected =
                        Instant.parse(open)
                                .plusSeconds(step)
                                .minus(micros ? 1 : 1000, ChronoUnit.MICROS);
                if (!row.get("close_time").equals(expected.toString()))
                    shortened.add(
                            Map.of(
                                    "open_time",
                                    open,
                                    "close_time",
                                    row.get("close_time").toString(),
                                    "nominal_close_time",
                                    expected.toString()));
            }
            Instant until = through.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            for (Instant time = from.atStartOfDay(ZoneOffset.UTC).toInstant();
                    time.isBefore(until);
                    time = time.plusSeconds(step))
                if (!times.contains(time.toString())) missing.add(time.toString());
        }
        if (dataset.traded()) {
            for (var line : csv(zip)) {
                var values = line.split(",", -1);
                if (values.length == 12
                        && Character.isDigit(values[0].charAt(0))
                        && invalidEmptyBar(
                                values, dataset.spot() && !from.isBefore(LocalDate.of(2025, 1, 1))))
                    excluded.add(
                            Map.of(
                                    "raw",
                                    line,
                                    "reason",
                                    "来源零成交占位行的 close_time 早于 open_time，隔离该行，不写价格"));
            }
        }
        return new Coverage(
                rows, List.copyOf(missing), List.copyOf(shortened), List.copyOf(excluded));
    }

    private static List<Map<String, Object>> parse(
            BinanceArchiveDataset dataset,
            byte[] zip,
            LocalDate from,
            LocalDate through,
            String sha,
            Instant observed,
            boolean retainPartial)
            throws Exception {
        if (through.isBefore(from)
                || !sha.matches("[a-f0-9]{64}")
                || observed.getNano() % 1000 != 0)
            throw new IllegalArgumentException("归档范围、摘要或时间精度不符");
        var lines = csv(zip);
        boolean funding = dataset == BinanceArchiveDataset.FUNDING;
        int start = 0;
        if (!Character.isDigit(lines.getFirst().charAt(0))) {
            String header = funding ? "calc_time,funding_interval_hours,last_funding_rate" : HEADER;
            if (!lines.getFirst().equals(header))
                throw new IllegalArgumentException("归档表头与已核实版本不同");
            start = 1;
        }
        var result = new ArrayList<Map<String, Object>>();
        Instant lower = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant upper = through.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant previous = null;
        long step = dataset.interval().equals("1d") ? 86400L : 3600L;
        for (int i = start; i < lines.size(); i++) {
            var values = lines.get(i).split(",", -1);
            if (values.length != (funding ? 3 : 12))
                throw new IllegalArgumentException("归档字段数量不符：行 " + (i + 1));
            // 现货精度按真实开盘日期分版，期货独立保持毫秒；不靠数值长度猜单位。
            boolean micros = dataset.spot() && !from.isBefore(LocalDate.of(2025, 1, 1));
            Instant time = time(values[0], micros);
            if (time.isBefore(lower)
                    || !time.isBefore(upper)
                    || previous != null && !time.isAfter(previous))
                throw new IllegalArgumentException("归档时间越界、重复或未升序");
            var row = new LinkedHashMap<String, Object>();
            row.put(funding ? "funding_time" : "open_time", time.toString());
            row.put("exchange", "BINANCE");
            row.put("symbol", "BTCUSDT");
            row.put("instrument", dataset.spot() ? "SPOT" : "USDT_PERPETUAL");
            if (funding) {
                int hours = new BigDecimal(values[1]).intValueExact();
                if (hours < 1 || hours > 24) throw new IllegalArgumentException("来源资金费率间隔超出当前支持范围");
                row.put("funding_interval_hours", hours);
                row.put("funding_rate", number(values[2], false));
            } else {
                if (time.getNano() != 0
                        || time.getEpochSecond() % step != 0
                        || !retainPartial
                                && previous != null
                                && Duration.between(previous, time).getSeconds() != step)
                    throw new IllegalArgumentException("K 线缺口或起点未按 UTC 对齐，保留该项待核对");
                Instant close = time(values[6], micros);
                Instant expectedClose =
                        time.plusSeconds(step).minus(micros ? 1 : 1000, ChronoUnit.MICROS);
                // 已见官方历史包中的坏占位行：无成交且结束早于开始。保留原文和缺口，不写这根价格。
                if (retainPartial && dataset.traded() && invalidEmptyBar(values, micros)) continue;
                if (close.isBefore(time)
                        || !close.isBefore(time.plusSeconds(step))
                        || !retainPartial && !close.equals(expectedClose))
                    throw new IllegalArgumentException("K 线收盘边界不符");
                row.put("interval_minutes", (int) (step / 60));
                row.put("close_time", close.toString());
                double open = number(values[1], true),
                        high = number(values[2], true),
                        low = number(values[3], true),
                        last = number(values[4], true);
                if (high < Math.max(open, last) || low > Math.min(open, last) || low > high)
                    throw new IllegalArgumentException("OHLC 价格关系不符");
                row.put("open", open);
                row.put("high", high);
                row.put("low", low);
                row.put("close", last);
                if (dataset.traded()) {
                    double volume = number(values[5], false), quote = number(values[7], false);
                    long count = new BigDecimal(values[8]).longValueExact();
                    double buy = number(values[9], false), buyQuote = number(values[10], false);
                    if (volume < 0
                            || quote < 0
                            || count < 0
                            || buy < 0
                            || buyQuote < 0
                            || buy > volume + 1e-8
                            || buyQuote > quote + 1e-8)
                        throw new IllegalArgumentException("成交量、笔数或主动买入量不符");
                    row.put("volume", volume);
                    row.put("quote_volume", quote);
                    row.put("trade_count", count);
                    row.put("taker_buy_volume", buy);
                    row.put("taker_buy_quote_volume", buyQuote);
                }
            }
            row.put("source_sha256", sha);
            row.put("observed_at", observed.toString());
            result.add(Collections.unmodifiableMap(row));
            previous = time;
        }
        if (result.isEmpty()) throw new IllegalArgumentException("归档没有数据行");
        if (!funding
                && !retainPartial
                && (result.size() != Duration.between(lower, upper).getSeconds() / step
                        || !result.getFirst().get("open_time").equals(lower.toString())))
            throw new IllegalArgumentException("K 线未覆盖请求的完整 UTC 范围，保留待核对");
        return List.copyOf(result);
    }

    private static boolean invalidEmptyBar(String[] values, boolean micros) {
        if (!time(values[6], micros).isBefore(time(values[0], micros))) return false;
        for (int i : List.of(5, 7, 8, 9, 10))
            if (new BigDecimal(values[i]).signum() != 0) return false;
        var price = new BigDecimal(values[1]);
        return price.signum() > 0
                && price.compareTo(new BigDecimal(values[2])) == 0
                && price.compareTo(new BigDecimal(values[3])) == 0
                && price.compareTo(new BigDecimal(values[4])) == 0;
    }

    private static double number(String value, boolean positive) {
        double number = new BigDecimal(value).doubleValue();
        if (!Double.isFinite(number) || positive && number <= 0)
            throw new IllegalArgumentException("数值非法");
        return number == 0 ? 0d : number;
    }

    private static Instant time(String value, boolean micros) {
        long raw = Long.parseLong(value);
        return micros
                ? Instant.ofEpochSecond(
                        Math.floorDiv(raw, 1_000_000), Math.floorMod(raw, 1_000_000) * 1000)
                : Instant.ofEpochMilli(raw);
    }

    /** 只接受一个平铺的有界 CSV；不解压到磁盘，也不接受路径越界。 */
    public static List<String> csv(byte[] bytes) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var entry = zip.getNextEntry();
            if (entry == null
                    || entry.isDirectory()
                    || !entry.getName().matches("[A-Za-z0-9_.-]+\\.csv"))
                throw new IllegalArgumentException("归档必须只有一个平铺 CSV");
            byte[] csv = zip.readNBytes(16 * 1024 * 1024 + 1);
            if (csv.length > 16 * 1024 * 1024 || zip.getNextEntry() != null)
                throw new IllegalArgumentException("归档解压超限或包含多个文件");
            var rows =
                    new String(csv, StandardCharsets.UTF_8)
                            .lines()
                            .filter(s -> !s.isBlank())
                            .toList();
            if (rows.isEmpty() || rows.size() > 100_001)
                throw new IllegalArgumentException("CSV 为空或超行数预算");
            return rows;
        }
    }
}
