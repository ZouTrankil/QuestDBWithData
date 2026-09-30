package com.zoutrankil.questdbwithdata.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.client.dto.TushareMarginAllDto;
import com.zoutrankil.questdbwithdata.domain.DatasetValues;
import com.zoutrankil.questdbwithdata.domain.MarginAll;
import com.zoutrankil.questdbwithdata.domain.MarginAllKey;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit D028 provider -> DTO -> typed row -> all-nine-column mapping. */
public final class MarginAllMapper {
    public TushareMarginAllDto dto(Map<String, JsonNode> row) {
        return new TushareMarginAllDto(required(row, "trade_date"), required(row, "exchange_id"),
                scalar(row, "rzye"), scalar(row, "rzmre"), scalar(row, "rzche"), scalar(row, "rqye"),
                scalar(row, "rqmcl"), scalar(row, "rzrqye"), scalar(row, "rqyl"));
    }
    public MarginAll fromSource(TushareMarginAllDto dto) {
        if (dto == null) throw new IllegalArgumentException("D028 source DTO required");
        LocalDate date = TemporalValues.businessDate(dto.tradeDate(), TemporalValues.DateFormat.BASIC);
        return new MarginAll(new MarginAllKey(date, dto.exchangeId()), finite(dto.rzye(), "rzye"),
                finite(dto.rzmre(), "rzmre"), finite(dto.rzche(), "rzche"), finite(dto.rqye(), "rqye"),
                finite(dto.rqmcl(), "rqmcl"), finite(dto.rzrqye(), "rzrqye"), finite(dto.rqyl(), "rqyl"));
    }
    public DatasetValues values(MarginAll row) {
        var v = new LinkedHashMap<String,Object>(); v.put("trade_date", row.tradeDate()); v.put("exchange_id", row.exchangeId());
        v.put("rzye", row.rzye()); v.put("rzmre", row.rzmre()); v.put("rzche", row.rzche()); v.put("rqye", row.rqye());
        v.put("rqmcl", row.rqmcl()); v.put("rzrqye", row.rzrqye()); v.put("rqyl", row.rqyl()); return new DatasetValues(v);
    }
    public MarginAll fromValues(DatasetValues v) {
        return new MarginAll(new MarginAllKey(v.get("trade_date", LocalDate.class), v.get("exchange_id", String.class)),
                v.get("rzye", Double.class), v.get("rzmre", Double.class), v.get("rzche", Double.class),
                v.get("rqye", Double.class), v.get("rqmcl", Double.class), v.get("rzrqye", Double.class), v.get("rqyl", Double.class));
    }
    private static String required(Map<String,JsonNode> row, String field) {
        JsonNode v = row.get(field);
        if (v == null || v.isNull() || !(v.isTextual() || v.isIntegralNumber()) || v.asText().isBlank())
            throw new IllegalArgumentException("Required D028 field missing: " + field);
        return v.asText();
    }
    private static String scalar(Map<String,JsonNode> row, String field) {
        JsonNode v = row.get(field);
        if (v == null || v.isNull()) throw new IllegalArgumentException("D028 Python model rejects null numeric value: " + field);
        if (v.isNumber() || v.isTextual()) return v.asText();
        throw new IllegalArgumentException("D028 numeric scalar required: " + field);
    }
    private static Double finite(String raw, String field) {
        try { double n = new BigDecimal(raw.strip()).doubleValue(); if (!Double.isFinite(n)) throw new NumberFormatException(); return n; }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid D028 numeric value: " + field, invalid); }
    }
}
