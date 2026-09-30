package com.zoutrankil.questdbwithdata.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.client.dto.L2DailyFeaturesParquetDto;
import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.DatasetValues;
import com.zoutrankil.questdbwithdata.domain.L2DailyFeatureField;
import com.zoutrankil.questdbwithdata.domain.L2DailyFeatures;
import com.zoutrankil.questdbwithdata.domain.L2DailyFeaturesDataset;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;

/** Explicit source DTO -> typed domain -> 110-column QuestDB mapping for D086. */
public final class L2DailyFeaturesMapper {
    public L2DailyFeatures fromParquet(JsonNode row, LocalDate expectedDate) throws IOException {
        var dto = L2DailyFeaturesParquetDto.decode(row, expectedDate);
        return new L2DailyFeatures(dto.tradeDate(), dto.symbol(), dto.features());
    }

    public DatasetValues values(L2DailyFeatures row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("ts", row.tradeDate());
        values.put("symbol", row.symbol());
        for (var field : L2DailyFeatureField.values())
            if (!field.identity()) values.put(field.fieldName(), row.features().get(field));
        return new DatasetValues(values);
    }

    public L2DailyFeatures fromValues(DatasetValues values) {
        LocalDate day = businessDate(values.get("ts", Object.class));
        String symbol = values.get("symbol", String.class);
        var features = new EnumMap<L2DailyFeatureField, Object>(L2DailyFeatureField.class);
        for (var field : L2DailyFeatureField.values()) {
            if (field.identity()) continue;
            features.put(field, field.normalize(value(values, field)));
        }
        return new L2DailyFeatures(day, symbol, features);
    }

    public static List<String> columns() {
        return L2DailyFeaturesDataset.DEFINITION.columns().stream()
                .map(DatasetDefinition.Column::logicalName).toList();
    }

    private static Object value(DatasetValues values, L2DailyFeatureField field) {
        return switch (field.storageType()) {
            case BOOLEAN -> values.get(field.fieldName(), Boolean.class);
            case LONG -> values.get(field.fieldName(), Long.class);
            case DOUBLE -> values.get(field.fieldName(), Double.class);
            case STRING, SYMBOL -> values.get(field.fieldName(), String.class);
            default -> throw new IllegalArgumentException("Unexpected D086 feature type: " + field.fieldName());
        };
    }

    private static LocalDate businessDate(Object value) {
        if (value instanceof LocalDate date) return date;
        if (value instanceof Instant instant) return instant.atZone(ZoneOffset.UTC).toLocalDate();
        if (value instanceof LocalDateTime dateTime) return dateTime.toLocalDate();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        if (value instanceof String text) return LocalDate.parse(text.substring(0, 10));
        throw new IllegalArgumentException("Unsupported D086 business timestamp read type");
    }
}
