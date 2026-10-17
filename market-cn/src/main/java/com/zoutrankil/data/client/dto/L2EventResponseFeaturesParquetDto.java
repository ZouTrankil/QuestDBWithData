package com.zoutrankil.data.client.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.L2EventResponseFeatureField;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;

/** Explicit 73-column D088 Parquet DTO before timezone and business-key normalization. */
public record L2EventResponseFeaturesParquetDto(LocalDate tradeDate, String symbol, String market,
        String board, LocalDateTime localMinute, String eventType,
        EnumMap<L2EventResponseFeatureField, Object> features) {
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public static L2EventResponseFeaturesParquetDto decode(JsonNode row, LocalDate expectedDate) throws IOException {
        if (row == null || !row.isObject() || row.size() != L2EventResponseFeatureField.values().length)
            throw new IOException("D088 Parquet row does not match the frozen 73-column schema");
        String rawDate = text(row, L2EventResponseFeatureField.TRADE_DATE);
        if (!rawDate.matches("[0-9]{8}")) throw new IOException("D088 trade_date must use YYYYMMDD");
        final LocalDate day;
        try { day = LocalDate.parse(rawDate, BASIC_DATE); }
        catch (DateTimeParseException failure) { throw new IOException("D088 trade_date is invalid", failure); }
        if (!day.equals(expectedDate)) throw new IOException("D088 row date differs from its source partition");
        String symbol = text(row, L2EventResponseFeatureField.SYMBOL);
        String market = text(row, L2EventResponseFeatureField.MARKET);
        String board = text(row, L2EventResponseFeatureField.BOARD);
        JsonNode minuteNode = row.get("minute");
        if (minuteNode == null || !minuteNode.isTextual() || minuteNode.textValue().isBlank())
            throw new IOException("D088 minute is missing");
        final LocalDateTime localMinute;
        try { localMinute = LocalDateTime.parse(minuteNode.textValue()); }
        catch (DateTimeParseException failure) {
            throw new IOException("D088 minute must be a timezone-naive local Parquet timestamp", failure);
        }
        if (!localMinute.toLocalDate().equals(day) || localMinute.getSecond() != 0 || localMinute.getNano() != 0)
            throw new IOException("D088 minute must be a whole minute within trade_date");
        String eventType = text(row, L2EventResponseFeatureField.EVENT_TYPE);
        if (!L2EventResponseFeatureField.EVENT_TYPE_ALLOWED.contains(eventType))
            throw new IOException("D088 event_type is outside the Python-defined event set");
        var features = new EnumMap<L2EventResponseFeatureField, Object>(L2EventResponseFeatureField.class);
        for (var field : L2EventResponseFeatureField.values())
            if (field.metric()) features.put(field, field.decode(row.get(field.fieldName())));
        return new L2EventResponseFeaturesParquetDto(day, symbol, market, board, localMinute, eventType, features);
    }

    private static String text(JsonNode row, L2EventResponseFeatureField field) throws IOException {
        Object value = field.decode(row.get(field.fieldName()));
        if (!(value instanceof String text) || text.isBlank())
            throw new IOException("D088 required source text is missing: " + field.fieldName());
        return text;
    }
}
