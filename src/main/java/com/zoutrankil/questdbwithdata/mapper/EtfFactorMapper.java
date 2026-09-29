package com.zoutrankil.questdbwithdata.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.client.dto.TushareEtfFactorDto;
import com.zoutrankil.questdbwithdata.domain.DatasetValues;
import com.zoutrankil.questdbwithdata.domain.EtfFactor;
import com.zoutrankil.questdbwithdata.domain.EtfFactorDataset;
import com.zoutrankil.questdbwithdata.domain.EtfFactorKey;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit same-name field mapping for fund_factor_pro -> typed source DTO -> domain -> QuestDB. */
public final class EtfFactorMapper {
    public static final List<String> SOURCE_FIELDS = EtfFactorDataset.sourceFields();
    private static final List<String> NUMERIC_FIELDS = EtfFactorDataset.NUMERIC_FIELDS;

    public TushareEtfFactorDto dto(Map<String, JsonNode> source) {
        Objects.requireNonNull(source);
        if (!source.keySet().containsAll(SOURCE_FIELDS))
            throw new IllegalArgumentException("fund_factor_pro response lacks one or more frozen physical fields");
        var factors = new LinkedHashMap<String, Double>();
        for (String field : NUMERIC_FIELDS) factors.put(field, number(source.get(field), field));
        return new TushareEtfFactorDto(text(source, "ts_code"), text(source, "trade_date"), factors);
    }

    public EtfFactor fromSource(TushareEtfFactorDto source) {
        Objects.requireNonNull(source);
        return new EtfFactor(new EtfFactorKey(source.tsCode(),
                TemporalValues.businessDate(source.tradeDate(), TemporalValues.DateFormat.BASIC)), source.factors());
    }

    public DatasetValues values(EtfFactor row) {
        Objects.requireNonNull(row);
        var values = new LinkedHashMap<String, Object>();
        values.put("ts_code", row.tsCode()); values.put("trade_date", row.tradeDate());
        values.putAll(row.factors());
        if (values.size() != EtfFactorDataset.DEFINITION.columns().size())
            throw new IllegalStateException("etf_factor mapper must cover all " + EtfFactorDataset.DEFINITION.columns().size() + " columns");
        return new DatasetValues(values);
    }

    public EtfFactor fromValues(DatasetValues values) {
        var key = new EtfFactorKey(values.get("ts_code", String.class), values.get("trade_date", LocalDate.class));
        var factors = new LinkedHashMap<String, Double>();
        for (String field : NUMERIC_FIELDS) factors.put(field, values.get(field, Double.class));
        return new EtfFactor(key, factors);
    }

    private static String text(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank())
            throw new IllegalArgumentException("Required nonblank fund_factor_pro field: " + field);
        return value.textValue();
    }
    private static Double number(JsonNode value, String field) {
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() && !value.isTextual())
            throw new IllegalArgumentException("Numeric fund_factor_pro scalar or null required: " + field);
        String raw = value.asText();
        if (raw.isBlank()) throw new IllegalArgumentException("Blank nonnull fund_factor_pro number: " + field);
        double parsed;
        try { parsed = new BigDecimal(raw).doubleValue(); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid fund_factor_pro number: " + field, invalid); }
        if (!Double.isFinite(parsed)) throw new IllegalArgumentException("Non-finite fund_factor_pro number: " + field);
        return parsed;
    }
}
