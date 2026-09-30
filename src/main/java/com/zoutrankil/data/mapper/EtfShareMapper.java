package com.zoutrankil.data.mapper;

import com.zoutrankil.data.domain.EtfShareDataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareEtfShareDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.EtfShare;
import com.zoutrankil.data.domain.EtfShareKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit source DTO/domain/storage mapping for all five fields accepted by the provider probe. */
public final class EtfShareMapper {
    public static final List<String> SOURCE_FIELDS = List.of("ts_code", "trade_date", "fd_share", "fund_type", "market");

    public TushareEtfShareDto dto(Map<String, JsonNode> row) {
        if (row == null || !row.keySet().containsAll(SOURCE_FIELDS))
            throw new IllegalArgumentException("All five declared fund_share fields are required");
        return new TushareEtfShareDto(text(row, "ts_code"), text(row, "trade_date"), number(row, "fd_share"),
                nullableText(row, "fund_type"), text(row, "market"));
    }

    public EtfShare fromSource(Map<String, JsonNode> row, String requestedMarket, Instant observedAt) {
        return fromSource(dto(row), requestedMarket, observedAt);
    }

    public EtfShare fromSource(TushareEtfShareDto source, String requestedMarket, Instant observedAt) {
        Objects.requireNonNull(source); Objects.requireNonNull(observedAt);
        EtfShareDataset.requireObservation(observedAt);
        var key = new EtfShareKey(source.tsCode(), TemporalValues.businessDate(source.tradeDate(), TemporalValues.DateFormat.BASIC));
        String suffix = key.tsCode().substring(key.tsCode().length() - 2);
        String suffixMarket = suffix.equals("OF") ? "O" : suffix;
        if (!suffixMarket.equals(requestedMarket) || !source.market().equals(requestedMarket))
            throw new IllegalArgumentException("fund_share returned a code outside requested market=" + requestedMarket);
        // fund_type is nullable provider data: retain explicit nulls because they can revise existing values.
        return new EtfShare(key, source.fdShare(), source.fundType(), source.market(), observedAt);
    }

    public DatasetValues values(EtfShare row) {
        Objects.requireNonNull(row);
        var values = new LinkedHashMap<String, Object>();
        values.put("ts_code", row.tsCode());
        values.put("trade_date", row.tradeDate());
        values.put("fd_share", row.fdShare());
        values.put("fund_type", row.fundType());
        values.put("market", row.market());
        values.put("update_time", row.updateTime());
        if (values.size() != EtfShareDataset.DEFINITION.columns().size())
            throw new IllegalStateException("etf_share mapper must cover all six physical columns");
        return new DatasetValues(values);
    }

    public EtfShare fromValues(DatasetValues values) {
        return new EtfShare(new EtfShareKey(values.get("ts_code", String.class), values.get("trade_date", LocalDate.class)),
                values.get("fd_share", Double.class), values.get("fund_type", String.class),
                values.get("market", String.class), values.get("update_time", Instant.class));
    }

    private static String text(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank())
            throw new IllegalArgumentException("Required nonblank text fund_share field: " + field);
        return value.textValue();
    }

    private static String nullableText(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank())
            throw new IllegalArgumentException("Text fund_share field or provider null required: " + field);
        return value.textValue();
    }

    private static Double number(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() && !value.isTextual())
            throw new IllegalArgumentException("Numeric fund_share field or null required: " + field);
        String raw = value.asText();
        if (raw.isBlank()) throw new IllegalArgumentException("Empty nonnull fund_share number: " + field);
        double parsed;
        try { parsed = new java.math.BigDecimal(raw).doubleValue(); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid fund_share number: " + field, invalid); }
        if (!Double.isFinite(parsed)) throw new IllegalArgumentException("Non-finite fund_share number: " + field);
        return parsed;
    }
}
