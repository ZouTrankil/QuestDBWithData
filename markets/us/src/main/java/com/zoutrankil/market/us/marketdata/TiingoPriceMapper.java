package com.zoutrankil.market.us.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** 保留 Tiingo 未复权价和来源调整价；现金额按已核对的发行人事件明确转换。 */
public final class TiingoPriceMapper {
    public static final String CASH_DIVIDEND_POLICY = "TIINGO_CASH_DIVIDEND_V3";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final String SEARS_COMMON = "US000000086368";
    private static final LocalDate LANDS_END_ADJUSTMENT_DATE = LocalDate.of(2014, 4, 7);
    private static final String LANDS_END_SOURCE =
            "https://www.sec.gov/Archives/edgar/data/799288/000119312514134340/d703946dex992.htm";

    /** 原文、实际现金额及事件依据同时交给发布方，避免只留下转换后的值。 */
    public record DailyMapping(
            List<Map<String, Object>> rows,
            List<Map<String, Object>> nonCashDistributions,
            List<Map<String, Object>> cashDividendCorrections) {
        public DailyMapping {
            rows = List.copyOf(rows);
            nonCashDistributions = List.copyOf(nonCashDistributions);
            cashDividendCorrections = List.copyOf(cashDividendCorrections);
        }
    }

    private TiingoPriceMapper() {}

    public static List<Map<String, Object>> daily(
            byte[] bytes, String symbol, String sha, Instant observed, LocalDate through)
            throws Exception {
        return dailyMapping(bytes, symbol, sha, observed, through).rows();
    }

    public static DailyMapping dailyMapping(
            byte[] bytes, String symbol, String sha, Instant observed, LocalDate through)
            throws Exception {
        symbol(symbol);
        JsonNode array = JSON.readTree(bytes);
        if (!array.isArray()) throw new IllegalArgumentException("Tiingo 日线响应必须是数组");
        var rows = new ArrayList<Map<String, Object>>();
        var nonCash = new ArrayList<Map<String, Object>>();
        var cashCorrections = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<LocalDate>();
        for (var source : array) {
            Instant marker = Instant.parse(source.required("date").asText());
            LocalDate date = marker.atZone(ZoneOffset.UTC).toLocalDate();
            if (!marker.equals(date.atStartOfDay(ZoneOffset.UTC).toInstant()))
                throw new IllegalArgumentException("日线业务日期必须是来源的 UTC 零点标记");
            if (date.isAfter(through)) continue;
            if (!seen.add(date)) throw new IllegalArgumentException("Tiingo 日线业务日期重复");
            var row = new LinkedHashMap<String, Object>();
            row.put("trade_date", date.atStartOfDay(ZoneOffset.UTC).toInstant().toString());
            row.put("symbol", symbol);
            row.put("provider", "TIINGO");
            row.put("venue", "US_COMPOSITE");
            for (String field : List.of("open", "high", "low", "close", "volume"))
                row.put(field, number(source, field));
            for (String field : List.of("Open", "High", "Low", "Close", "Volume"))
                row.put("adj_" + field.toLowerCase(Locale.ROOT), number(source, "adj" + field));
            row.put("dividend", number(source, "divCash"));
            row.put("split_factor", number(source, "splitFactor"));
            if (symbol.equals(SEARS_COMMON) && date.equals(LANDS_END_ADJUSTMENT_DATE)) {
                // 2014 年实际分派 LE 股票；供应商用现金等价额调整价格，不能把它记成实收现金或数量拆股。
                if (Double.compare((double) row.get("dividend"), 9.52618) != 0
                        || Double.compare((double) row.get("split_factor"), 1.0) != 0)
                    throw new IllegalArgumentException("已核对非现金分拆的来源金额或数量因子变化，保留原文核对");
                row.put("dividend", 0.0);
                nonCash.add(
                        Map.ofEntries(
                                Map.entry("policy", CASH_DIVIDEND_POLICY),
                                Map.entry("eventType", "NON_CASH_SPIN_OFF"),
                                Map.entry("symbol", SEARS_COMMON),
                                Map.entry("sourceAdjustmentDate", date.toString()),
                                Map.entry("distributionDate", "2014-04-04"),
                                Map.entry("sourceDivCash", 9.52618),
                                Map.entry("cashDividend", 0.0),
                                Map.entry("distributedSymbol", "LE"),
                                Map.entry("sharesPerShare", 0.300795),
                                Map.entry("primarySourceUrl", LANDS_END_SOURCE)));
            }
            var cashCorrection = TiingoVerifiedCashDividends.find(symbol, date);
            if (cashCorrection != null) {
                // 永久证券、来源日、原金额和数量因子同时命中，才补足发行人已明确的现金金额。
                if (Double.compare((double) row.get("dividend"), cashCorrection.sourceCash()) != 0
                        || Double.compare((double) row.get("split_factor"), 1.0) != 0)
                    throw new IllegalArgumentException("已核对现金分红的来源金额或数量因子变化，保留原文核对");
                row.put("dividend", cashCorrection.cashDividend());
                cashCorrections.add(
                        Map.ofEntries(
                                Map.entry("policy", CASH_DIVIDEND_POLICY),
                                Map.entry("eventType", "VERIFIED_CASH_AMOUNT"),
                                Map.entry("symbol", symbol),
                                Map.entry("sourceAdjustmentDate", date.toString()),
                                Map.entry("sourceDivCash", cashCorrection.sourceCash()),
                                Map.entry("cashDividend", cashCorrection.cashDividend()),
                                Map.entry("primarySourceUrl", cashCorrection.primarySourceUrl())));
            }
            if ((double) row.get("high") < (double) row.get("low")
                    || (double) row.get("volume") < 0
                    || (double) row.get("split_factor") <= 0)
                throw new IllegalArgumentException("Tiingo 价格、成交量或拆股因子非法");
            row.put("currency", "USD");
            row.put("raw_price_basis", "VENDOR_UNADJUSTED");
            row.put("source_sha256", sha);
            row.put("observed_at", observed.toString());
            rows.add(Collections.unmodifiableMap(row));
        }
        rows.sort(Comparator.comparing(row -> row.get("trade_date").toString()));
        return new DailyMapping(rows, nonCash, cashCorrections);
    }

    public static List<Map<String, Object>> minute(
            byte[] bytes,
            String symbol,
            String sha,
            Instant observed,
            LocalDate from,
            LocalDate through)
            throws Exception {
        symbol(symbol);
        JsonNode array = JSON.readTree(bytes);
        // 已实测接口会悄然只返回最后一万根，达到边界即拒绝，交给按月拆分的来源执行器。
        if (!array.isArray() || array.size() >= 10000)
            throw new IllegalArgumentException("分钟响应达到一万根边界或格式错误，必须缩小日期范围");
        var rows = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<Instant>();
        for (var source : array) {
            Instant ts = Instant.parse(source.required("date").asText());
            LocalDate day = ts.atZone(NEW_YORK).toLocalDate();
            if (day.isBefore(from) || day.isAfter(through))
                throw new IllegalArgumentException("分钟来源返回请求范围外的日期");
            if (ts.getNano() != 0 || ts.getEpochSecond() % 60 != 0 || !seen.add(ts))
                throw new IllegalArgumentException("分钟起点必须唯一且对齐整分钟");
            var row = new LinkedHashMap<String, Object>();
            row.put("ts", ts.toString());
            row.put("trade_date", day.atStartOfDay(ZoneOffset.UTC).toInstant().toString());
            row.put("symbol", symbol);
            row.put("provider", "TIINGO_IEX");
            row.put("venue", "IEX");
            row.put("interval_minutes", 1);
            for (String field : List.of("open", "high", "low", "close"))
                row.put(field, number(source, field));
            row.put("volume", source.hasNonNull("volume") ? number(source, "volume") : null);
            if ((double) row.get("high") < (double) row.get("low")
                    || row.get("volume") instanceof Double v && v < 0)
                throw new IllegalArgumentException("分钟价格或成交量非法");
            row.put("source_sha256", sha);
            row.put("observed_at", observed.toString());
            rows.add(Collections.unmodifiableMap(row));
        }
        rows.sort(Comparator.comparing(row -> row.get("ts").toString()));
        return List.copyOf(rows);
    }

    /** 只合并同一纽约交易日和来源的实有分钟；保留缺分钟，不能把稀疏成交补成零量行情。 */
    public static List<Map<String, Object>> fiveMinute(List<Map<String, Object>> minute) {
        var groups = new TreeMap<Instant, List<Map<String, Object>>>();
        for (var row : minute) {
            Instant ts = Instant.parse(row.get("ts").toString());
            groups.computeIfAbsent(
                            Instant.ofEpochSecond(Math.floorDiv(ts.getEpochSecond(), 300) * 300),
                            k -> new ArrayList<>())
                    .add(row);
        }
        var result = new ArrayList<Map<String, Object>>();
        for (var group : groups.entrySet()) {
            var values = group.getValue();
            values.sort(Comparator.comparing(v -> v.get("ts").toString()));
            var first = values.getFirst();
            if (values.stream()
                    .anyMatch(
                            v ->
                                    !v.get("symbol").equals(first.get("symbol"))
                                            || !v.get("trade_date").equals(first.get("trade_date"))
                                            || !v.get("provider").equals(first.get("provider"))))
                throw new IllegalArgumentException("五分钟合并不能混合证券、日期或来源");
            var row = new LinkedHashMap<>(first);
            row.put("ts", group.getKey().toString());
            row.put("interval_minutes", 5);
            row.put(
                    "high",
                    values.stream()
                            .mapToDouble(v -> ((Number) v.get("high")).doubleValue())
                            .max()
                            .orElseThrow());
            row.put(
                    "low",
                    values.stream()
                            .mapToDouble(v -> ((Number) v.get("low")).doubleValue())
                            .min()
                            .orElseThrow());
            row.put("close", values.getLast().get("close"));
            row.put(
                    "volume",
                    values.stream().anyMatch(v -> v.get("volume") == null)
                            ? null
                            : values.stream()
                                    .mapToDouble(v -> ((Number) v.get("volume")).doubleValue())
                                    .sum());
            result.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(result);
    }

    private static double number(JsonNode row, String field) {
        var value = row.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException("Tiingo 字段必须是有限数值：" + field);
        return value.doubleValue();
    }

    private static void symbol(String symbol) {
        if (symbol == null || !symbol.matches("[A-Z0-9][A-Z0-9.\\-^]{0,31}"))
            throw new IllegalArgumentException("证券代码非法");
    }
}
