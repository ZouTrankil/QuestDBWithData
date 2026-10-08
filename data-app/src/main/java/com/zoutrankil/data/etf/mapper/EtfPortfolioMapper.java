package com.zoutrankil.data.etf.mapper;

import com.zoutrankil.data.domain.EtfPortfolioDataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareEtfPortfolioDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.EtfPortfolio;
import com.zoutrankil.data.domain.EtfPortfolioKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit fund_portfolio DTO/domain/physical mapping; no unit conversion or synthetic source columns. */
public final class EtfPortfolioMapper {
    public static final List<String> SOURCE_FIELDS = List.of("ts_code", "ann_date", "end_date", "symbol",
            "mkv", "amount", "stk_mkv_ratio", "stk_float_ratio");

    public TushareEtfPortfolioDto dto(Map<String, JsonNode> row) {
        if (row == null || !row.keySet().equals(new java.util.HashSet<>(SOURCE_FIELDS)))
            throw new IllegalArgumentException("Exactly the eight declared fund_portfolio response fields required");
        return new TushareEtfPortfolioDto(text(row, "ts_code"), text(row, "ann_date"), text(row, "end_date"),
                text(row, "symbol"), number(row, "mkv"), number(row, "amount"),
                number(row, "stk_mkv_ratio"), number(row, "stk_float_ratio"));
    }

    public EtfPortfolio fromSource(Map<String, JsonNode> row, LocalDate requestedAnnDate, Instant observedAt) {
        return fromSource(dto(row), requestedAnnDate, observedAt);
    }

    public EtfPortfolio fromSource(TushareEtfPortfolioDto source, LocalDate requestedAnnDate, Instant observedAt) {
        Objects.requireNonNull(source); Objects.requireNonNull(requestedAnnDate); Objects.requireNonNull(observedAt);
        TemporalValues.requirePrecision(observedAt, TemporalValues.Precision.MICROS);
        var key = new EtfPortfolioKey(source.tsCode(),
                TemporalValues.businessDate(source.annDate(), TemporalValues.DateFormat.BASIC),
                TemporalValues.businessDate(source.endDate(), TemporalValues.DateFormat.BASIC), source.symbol());
        if (!key.annDate().equals(requestedAnnDate))
            throw new IllegalArgumentException("fund_portfolio row escaped frozen ann_date request");
        return new EtfPortfolio(key, source.marketValue(), source.amount(), source.stockMarketValueRatio(),
                source.stockFloatRatio(), observedAt);
    }

    public DatasetValues values(EtfPortfolio row) {
        Objects.requireNonNull(row);
        var values = new LinkedHashMap<String, Object>();
        values.put("ts_code", row.tsCode()); values.put("ann_date", row.annDate());
        values.put("end_date", row.endDate()); values.put("symbol", row.symbol());
        values.put("mkv", row.mkv()); values.put("amount", row.amount());
        values.put("stk_mkv_ratio", row.stkMkvRatio()); values.put("stk_float_ratio", row.stkFloatRatio());
        values.put("update_time", row.updateTime());
        if (values.size() != EtfPortfolioDataset.DEFINITION.columns().size())
            throw new IllegalStateException("etf_portfolio mapper must cover all nine physical columns");
        return new DatasetValues(values);
    }

    public EtfPortfolio fromValues(DatasetValues values) {
        return new EtfPortfolio(new EtfPortfolioKey(values.get("ts_code", String.class),
                values.get("ann_date", LocalDate.class), values.get("end_date", LocalDate.class),
                values.get("symbol", String.class)), values.get("mkv", Double.class), values.get("amount", Double.class),
                values.get("stk_mkv_ratio", Double.class), values.get("stk_float_ratio", Double.class),
                values.get("update_time", Instant.class));
    }

    private static String text(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank())
            throw new IllegalArgumentException("Required nonblank text fund_portfolio field: " + field);
        return value.textValue();
    }

    private static Double number(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() && !value.isTextual())
            throw new IllegalArgumentException("Numeric fund_portfolio field or null required: " + field);
        String raw = value.asText();
        if (raw.isBlank()) throw new IllegalArgumentException("Empty nonnull fund_portfolio number: " + field);
        double parsed;
        try { parsed = new BigDecimal(raw).doubleValue(); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid fund_portfolio number: " + field, invalid); }
        if (!Double.isFinite(parsed)) throw new IllegalArgumentException("Non-finite fund_portfolio number: " + field);
        return parsed;
    }
}
