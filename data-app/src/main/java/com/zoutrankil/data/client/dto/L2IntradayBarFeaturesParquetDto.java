package com.zoutrankil.data.client.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.L2IntradayBarFeatureField;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;

/** Explicit D087 wire DTO for one source Parquet row before time-zone normalization. */
public record L2IntradayBarFeaturesParquetDto(LocalDate tradeDate, String symbol, String market,
                                               String board, LocalDateTime localMinute,
                                               EnumMap<L2IntradayBarFeatureField, Object> features) {
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public static L2IntradayBarFeaturesParquetDto decode(JsonNode row, LocalDate expectedDate) throws IOException {
        if (row == null || !row.isObject() || row.size() != L2IntradayBarFeatureField.values().length)
            throw new IOException("D087 Parquet row does not match the frozen 60-column schema");
        String rawDate = text(row, L2IntradayBarFeatureField.TRADE_DATE);
        if (!rawDate.matches("[0-9]{8}")) throw new IOException("D087 trade_date must use YYYYMMDD");
        final LocalDate day;
        try { day = LocalDate.parse(rawDate, BASIC_DATE); }
        catch (DateTimeParseException failure) { throw new IOException("D087 trade_date is invalid", failure); }
        if (!day.equals(expectedDate)) throw new IOException("D087 row date differs from its source partition");
        String symbol = text(row, L2IntradayBarFeatureField.SYMBOL);
        String market = text(row, L2IntradayBarFeatureField.MARKET);
        String board = text(row, L2IntradayBarFeatureField.BOARD);
        JsonNode minuteNode = row.get(L2IntradayBarFeatureField.MINUTE.fieldName());
        if (minuteNode == null || !minuteNode.isTextual() || minuteNode.textValue().isBlank())
            throw new IOException("D087 minute is missing");
        String minuteText = minuteNode.textValue();
        final LocalDateTime localMinute;
        try { localMinute = LocalDateTime.parse(minuteText); }
        catch (DateTimeParseException failure) {
            throw new IOException("D087 minute must be a timezone-naive local Parquet timestamp", failure);
        }
        if (!localMinute.toLocalDate().equals(day) || localMinute.getSecond() != 0 || localMinute.getNano() != 0)
            throw new IOException("D087 minute must be a whole minute within trade_date");
        var features = new EnumMap<L2IntradayBarFeatureField, Object>(L2IntradayBarFeatureField.class);
        for (var field : L2IntradayBarFeatureField.values()) {
            if (field.metric()) features.put(field, field.decode(row.get(field.fieldName())));
        }
        return new L2IntradayBarFeaturesParquetDto(day, symbol, market, board, localMinute, features);
    }

    private static String text(JsonNode row, L2IntradayBarFeatureField field) throws IOException {
        Object value = field.decode(row.get(field.fieldName()));
        if (!(value instanceof String text) || text.isBlank())
            throw new IOException("D087 required source text is missing: " + field.fieldName());
        return text;
    }
}
