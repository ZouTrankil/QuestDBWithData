package com.zoutrankil.data.strategy.input;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 覆盖协议显式分版；v2 键摘要绑定角色和字段，不能复用 v1 的纯值摘要。 */
public enum StrategyInputSchema {
    V1(1, legacyKeys()),
    V2(2, standardKeys());

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DATES =
            Set.of("trade_date", "calendar_date", "announcement_date", "report_period_end_date");
    private final int version;
    private final Map<String, List<String>> keys;

    StrategyInputSchema(int version, Map<String, List<String>> keys) {
        this.version = version;
        this.keys = keys;
    }

    public Map<String, List<String>> keys() {
        return keys;
    }

    public String inputSchema() {
        return "java.strategy.inputs.v" + version;
    }

    public String coverageSchema() {
        return "java.strategy.source_coverage.v" + version;
    }

    public String approvalSchema() {
        return "java.strategy.coverage_inputs.v" + version;
    }

    public String keyEncoding() {
        return "java.strategy.source_keys.v" + version;
    }

    public static StrategyInputSchema fromCoverage(String schema) {
        for (var version : values()) if (version.coverageSchema().equals(schema)) return version;
        throw new IllegalStateException("不支持的来源覆盖版本：" + schema);
    }

    public static StrategyInputSchema fromApproval(String schema) {
        for (var version : values()) if (version.approvalSchema().equals(schema)) return version;
        throw new IllegalStateException("不支持的批准输入版本：" + schema);
    }

    String keySha(String role, List<Map<String, Object>> rows) throws Exception {
        var fields = keys.get(role);
        if (fields == null) throw new IllegalStateException("未登记的来源角色：" + role);
        if (this == V1) return StrategySourceCoverage.keySha(rows, fields);
        var tuples = new TreeSet<String>();
        for (var row : rows) {
            var tuple = new ArrayList<String>();
            for (String field : fields) {
                Object value = row.get(field);
                String text;
                if (DATES.contains(field)) {
                    if (!(value instanceof String) && !(value instanceof LocalDate))
                        throw new IllegalStateException(
                                "v2 日期键必须是 LocalDate 或 ISO 日期：" + role + "." + field);
                    text = value.toString();
                    try {
                        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                                || !LocalDate.parse(text).toString().equals(text))
                            throw new DateTimeParseException("非规范日期", text, 0);
                    } catch (DateTimeParseException invalid) {
                        throw new IllegalStateException(
                                "v2 日期键必须是 yyyy-MM-dd：" + role + "." + field, invalid);
                    }
                } else {
                    if (!(value instanceof String code)
                            || code.isBlank()
                            || !code.equals(code.strip()))
                        throw new IllegalStateException("v2 标识键为空或非规范文本：" + role + "." + field);
                    text = code;
                }
                tuple.add(text);
            }
            if (!tuples.add(JSON.writeValueAsString(tuple)))
                throw new IllegalStateException("评分输入业务键重复");
        }
        var digest = MessageDigest.getInstance("SHA-256");
        update(digest, JSON.writeValueAsString(List.of(keyEncoding(), role, fields)));
        for (String tuple : tuples) update(digest, tuple);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static Map<String, List<String>> legacyKeys() {
        return Map.ofEntries(
                Map.entry("stock_daily", List.of("trade_date", "ts_code")),
                Map.entry("l2_raw", List.of("ts", "symbol")),
                Map.entry("etf_factor", List.of("trade_date", "ts_code")),
                Map.entry("etf_daily", List.of("timestamp", "ts_code")),
                Map.entry("stock_metadata", List.of("ts_code")),
                Map.entry("etf_reference", List.of("ts_code")),
                Map.entry("etf_portfolio", List.of("ts_code", "ann_date", "end_date", "symbol")),
                Map.entry("sentiment", List.of("trade_date")),
                Map.entry("market_context", List.of("trade_date")),
                Map.entry("calendar", List.of("exchange", "cal_date")));
    }

    private static Map<String, List<String>> standardKeys() {
        return Map.ofEntries(
                Map.entry("stock_daily", List.of("trade_date", "instrument_code")),
                Map.entry("l2_raw", List.of("trade_date", "instrument_code")),
                Map.entry("etf_factor", List.of("trade_date", "instrument_code")),
                Map.entry("etf_daily", List.of("trade_date", "instrument_code")),
                Map.entry("stock_metadata", List.of("instrument_code")),
                Map.entry("etf_reference", List.of("instrument_code")),
                Map.entry(
                        "etf_portfolio",
                        List.of(
                                "etf_code",
                                "announcement_date",
                                "report_period_end_date",
                                "constituent_code")),
                Map.entry("sentiment", List.of("trade_date")),
                Map.entry("market_context", List.of("trade_date")),
                Map.entry("calendar", List.of("exchange", "calendar_date")));
    }
}
