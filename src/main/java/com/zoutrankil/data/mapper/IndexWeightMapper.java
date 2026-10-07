package com.zoutrankil.data.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.IndexWeightSourceDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.IndexWeight;
import com.zoutrankil.data.domain.IndexWeightKey;
import com.zoutrankil.data.domain.policy.IndexWeightUniverse;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit D021 source-to-domain-to-storage mapping for the two provider routes. */
public final class IndexWeightMapper {
    public IndexWeightSourceDto dto(Map<String, JsonNode> row) {
        return new IndexWeightSourceDto(text(row, "index_code"), text(row, "con_code"), text(row, "trade_date"),
                optionalText(row, "index_name"), optionalText(row, "index_name_en"), optionalText(row, "con_name"),
                optionalText(row, "con_name_en"), optionalText(row, "exchange"), optionalText(row, "exchange_en"),
                number(row, "weight"));
    }
    public IndexWeight fromSource(IndexWeightSourceDto source, Instant observedAt) {
        if (source == null) throw new IllegalArgumentException("Canonical D021 source row required");
        String indexCode = normalizeIndexCode(source.indexCode());
        String conCode = normalizeConCode(source.conCode(), source.exchange(), source.exchangeEn());
        LocalDate tradeDate = TemporalValues.businessDate(source.tradeDate(), TemporalValues.DateFormat.BASIC);
        return new IndexWeight(new IndexWeightKey(indexCode, conCode, tradeDate), source.indexName(), source.indexNameEn(),
                source.conName(), source.conNameEn(), source.exchange(), source.exchangeEn(), source.weight(), observedAt);
    }
    public DatasetValues values(IndexWeight row) {
        var values = new LinkedHashMap<String,Object>();
        values.put("index_code", row.indexCode()); values.put("con_code", row.conCode());
        values.put("trade_date", row.tradeDate()); values.put("index_name", row.indexName());
        values.put("index_name_en", row.indexNameEn()); values.put("con_name", row.conName());
        values.put("con_name_en", row.conNameEn()); values.put("exchange", row.exchange());
        values.put("exchange_en", row.exchangeEn()); values.put("weight", row.weight());
        values.put("update_time", row.updateTime());
        return new DatasetValues(values);
    }
    public IndexWeight fromValues(DatasetValues values) {
        return new IndexWeight(new IndexWeightKey(values.get("index_code", String.class),
                values.get("con_code", String.class), values.get("trade_date", LocalDate.class)),
                values.get("index_name", String.class), values.get("index_name_en", String.class),
                values.get("con_name", String.class), values.get("con_name_en", String.class),
                values.get("exchange", String.class), values.get("exchange_en", String.class),
                values.get("weight", Double.class), values.get("update_time", Instant.class));
    }
    public static String normalizeIndexCode(String value) {
        return IndexWeightUniverse.normalizeIndexCode(value);
    }
    public static String normalizeDateText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("D021 trade_date required");
        String text = value.strip();
        LocalDate date;
        try {
            if (text.matches("[0-9]{8}")) date = LocalDate.parse(text, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            else date = LocalDate.parse(text, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("D021 trade_date must be YYYYMMDD or ISO calendar date", invalid);
        }
        return date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }
    public static String normalizeConCode(String value, String exchange, String exchangeEn) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("con_code required");
        String code = value.strip().toUpperCase(java.util.Locale.ROOT);
        if (code.matches("[0-9]{6}\\.[A-Z]{2,4}")) return code;
        if (!code.matches("[0-9]{1,6}")) throw new IllegalArgumentException("Invalid constituent code: " + value);
        code = "0".repeat(6 - code.length()) + code;
        String marketText = exchange == null || exchange.isBlank() ? exchangeEn : exchange;
        String market = marketText == null ? "" : marketText.toUpperCase(java.util.Locale.ROOT);
        String suffix;
        if (market.contains("上海") || market.contains("SHANGHAI") || market.equals("SH") || market.equals("SSE")) suffix = "SH";
        else if (market.contains("深圳") || market.contains("SHENZHEN") || market.equals("SZ") || market.equals("SZSE")) suffix = "SZ";
        else if (market.contains("BJ") || market.contains("北京") || code.startsWith("8")
                || code.startsWith("4") || code.startsWith("9")) suffix = "BJ";
        else if (code.startsWith("6")) suffix = "SH";
        else suffix = "SZ";
        return code + "." + suffix;
    }
    private static String text(Map<String,JsonNode> row, String field) {
        JsonNode node = row.get(field);
        if (node == null || node.isNull()) throw new IllegalArgumentException("Required D021 source text missing: " + field);
        if (node.isTextual() && !node.asText().isBlank()) return node.asText();
        if (node.isNumber() && node.isIntegralNumber()) return node.asText();
        throw new IllegalArgumentException("Invalid D021 source text: " + field);
    }
    private static String optionalText(Map<String,JsonNode> row, String field) {
        JsonNode node = row.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalArgumentException("Invalid optional D021 source text: " + field);
        return node.asText();
    }
    private static Double number(Map<String,JsonNode> row, String field) {
        JsonNode node = row.get(field);
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (!node.isNumber() || !Double.isFinite(node.doubleValue()))
            throw new IllegalArgumentException("Finite D021 source number required: " + field);
        return node.doubleValue();
    }
}
