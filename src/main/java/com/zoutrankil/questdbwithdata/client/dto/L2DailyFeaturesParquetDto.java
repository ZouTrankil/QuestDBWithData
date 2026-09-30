package com.zoutrankil.questdbwithdata.client.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.L2DailyFeatureField;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Parsed source DTO; physical ts is validated against the source trade-date partition. */
public record L2DailyFeaturesParquetDto(LocalDate tradeDate, String symbol,
                                        Map<L2DailyFeatureField, Object> features) {
    public L2DailyFeaturesParquetDto {
        features = Collections.unmodifiableMap(new EnumMap<>(features));
    }

    public static L2DailyFeaturesParquetDto decode(JsonNode row, LocalDate expectedDate) throws IOException {
        if (row == null || !row.isObject() || row.size() != L2DailyFeatureField.values().length)
            throw new IOException("D086 row field count differs from the frozen 110-column schema");
        JsonNode ts = row.get("ts");
        JsonNode symbol = row.get("symbol");
        if (ts == null || !ts.isTextual() || symbol == null || !symbol.isTextual())
            throw new IOException("D086 row identity fields are missing or have the wrong type");
        String text = ts.textValue();
        if (text.length() < 10) throw new IOException("D086 ts is not a date timestamp");
        LocalDate day;
        try { day = LocalDate.parse(text.substring(0, 10)); }
        catch (RuntimeException failure) { throw new IOException("D086 ts is not an ISO date", failure); }
        if (!expectedDate.equals(day)) throw new IOException("D086 ts differs from its manifest trade date");
        var features = new EnumMap<L2DailyFeatureField, Object>(L2DailyFeatureField.class);
        for (var field : L2DailyFeatureField.values()) {
            JsonNode value = row.get(field.fieldName());
            if (value == null) throw new IOException("D086 field is missing: " + field.fieldName());
            if (field.identity()) continue;
            features.put(field, field.decode(value));
        }
        return new L2DailyFeaturesParquetDto(day, symbol.textValue(), features);
    }
}
