package com.zoutrankil.data.flow.mapper;


import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareMoneyflowThsDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.MoneyflowThs;
import com.zoutrankil.data.domain.MoneyflowThsKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit D025 raw DTO -> typed domain -> physical columns mapping. */
public final class MoneyflowThsMapper {
    public TushareMoneyflowThsDto dto(Map<String, JsonNode> row) {
        return new TushareMoneyflowThsDto(requiredScalar(row, "ts_code"), requiredScalar(row, "trade_date"),
                optionalText(row, "name"), scalar(row, "pct_change"), scalar(row, "latest"),
                scalar(row, "net_amount"), scalar(row, "net_d5_amount"), scalar(row, "buy_lg_amount"),
                scalar(row, "buy_lg_amount_rate"), scalar(row, "buy_md_amount"), scalar(row, "buy_md_amount_rate"),
                scalar(row, "buy_sm_amount"), scalar(row, "buy_sm_amount_rate"));
    }

    public MoneyflowThs fromSource(TushareMoneyflowThsDto source) {
        if (source == null) throw new IllegalArgumentException("D025 source DTO required");
        LocalDate date = TemporalValues.businessDate(source.tradeDate(), TemporalValues.DateFormat.BASIC);
        return new MoneyflowThs(new MoneyflowThsKey(source.tsCode(), date), source.name(),
                finite(source.pctChange(), "pct_change"), finite(source.latest(), "latest"),
                finite(source.netAmount(), "net_amount"), finite(source.netD5Amount(), "net_d5_amount"),
                finite(source.buyLgAmount(), "buy_lg_amount"), finite(source.buyLgAmountRate(), "buy_lg_amount_rate"),
                finite(source.buyMdAmount(), "buy_md_amount"), finite(source.buyMdAmountRate(), "buy_md_amount_rate"),
                finite(source.buySmAmount(), "buy_sm_amount"), finite(source.buySmAmountRate(), "buy_sm_amount_rate"));
    }

    public DatasetValues values(MoneyflowThs row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("ts_code", row.tsCode()); values.put("trade_date", row.tradeDate()); values.put("name", row.name());
        values.put("pct_change", row.pctChange()); values.put("latest", row.latest());
        values.put("net_amount", row.netAmount()); values.put("net_d5_amount", row.netD5Amount());
        values.put("buy_lg_amount", row.buyLgAmount()); values.put("buy_lg_amount_rate", row.buyLgAmountRate());
        values.put("buy_md_amount", row.buyMdAmount()); values.put("buy_md_amount_rate", row.buyMdAmountRate());
        values.put("buy_sm_amount", row.buySmAmount()); values.put("buy_sm_amount_rate", row.buySmAmountRate());
        return new DatasetValues(values);
    }

    public MoneyflowThs fromValues(DatasetValues values) {
        return new MoneyflowThs(new MoneyflowThsKey(values.get("ts_code", String.class),
                values.get("trade_date", LocalDate.class)), values.get("name", String.class),
                values.get("pct_change", Double.class), values.get("latest", Double.class),
                values.get("net_amount", Double.class), values.get("net_d5_amount", Double.class),
                values.get("buy_lg_amount", Double.class), values.get("buy_lg_amount_rate", Double.class),
                values.get("buy_md_amount", Double.class), values.get("buy_md_amount_rate", Double.class),
                values.get("buy_sm_amount", Double.class), values.get("buy_sm_amount_rate", Double.class));
    }

    public static String basicDate(LocalDate date) { return date.format(DateTimeFormatter.BASIC_ISO_DATE); }

    private static Double finite(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        try {
            double value = new BigDecimal(raw.strip()).doubleValue();
            if (!Double.isFinite(value)) throw new NumberFormatException("non-finite");
            return value;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid D025 numeric field " + field, invalid); }
    }
    private static String requiredScalar(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || !(value.isTextual() || value.isIntegralNumber()))
            throw new IllegalArgumentException("Required D025 scalar missing: " + field);
        String text = value.asText();
        if (text.isBlank()) throw new IllegalArgumentException("Required D025 scalar blank: " + field);
        return text;
    }
    private static String optionalText(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Optional D025 text field is not text: " + field);
        return value.asText();
    }
    private static String scalar(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isNumber() || value.isTextual()) return value.asText();
        throw new IllegalArgumentException("D025 metric must be a scalar: " + field);
    }
}
