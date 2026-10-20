package com.zoutrankil.data.l2.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.L2DailyFeaturesParquetDto;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.l2.L2DailyFeatureField;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesDataset;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.io.IOException;
import java.sql.Timestamp;
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
        return values(row, CnTradeDateLayout.LEGACY_V1);
    }

    public DatasetValues values(L2DailyFeatures row, CnTradeDateLayout layout) {
        var values = new LinkedHashMap<String, Object>();
        values.put(layout.physicalDateColumn("l2_daily_features"), row.tradeDate());
        values.put("symbol", row.symbol());
        for (var field : L2DailyFeatureField.values())
            if (!field.identity()) values.put(field.fieldName(), row.features().get(field));
        return new DatasetValues(values);
    }

    public L2DailyFeatures fromValues(DatasetValues values) {
        return fromValues(values, CnTradeDateLayout.LEGACY_V1);
    }

    public L2DailyFeatures fromValues(DatasetValues values, CnTradeDateLayout layout) {
        LocalDate day =
                businessDate(
                        values.get(layout.physicalDateColumn("l2_daily_features"), Object.class),
                        layout);
        String symbol = values.get("symbol", String.class);
        var features = new EnumMap<L2DailyFeatureField, Object>(L2DailyFeatureField.class);
        for (var field : L2DailyFeatureField.values()) {
            if (field.identity()) continue;
            features.put(field, field.normalize(value(values, field)));
        }
        return new L2DailyFeatures(day, symbol, features);
    }

    public static List<String> columns() {
        return columns(CnTradeDateLayout.LEGACY_V1);
    }

    public static List<String> columns(CnTradeDateLayout layout) {
        return L2DailyFeaturesDataset.definition("l2_daily_features", layout).columns().stream()
                .map(DatasetDefinition.Column::logicalName)
                .toList();
    }

    private static Object value(DatasetValues values, L2DailyFeatureField field) {
        return switch (field.storageType()) {
            case BOOLEAN -> values.get(field.fieldName(), Boolean.class);
            case LONG -> values.get(field.fieldName(), Long.class);
            case DOUBLE -> values.get(field.fieldName(), Double.class);
            case STRING, SYMBOL -> values.get(field.fieldName(), String.class);
            default ->
                    throw new IllegalArgumentException(
                            "Unexpected D086 feature type: " + field.fieldName());
        };
    }

    private static LocalDate businessDate(Object value, CnTradeDateLayout layout) {
        // 新版日期载体须是 UTC 午夜，不能截断时刻掩盖来源错误；v1 解码保持原样。
        if (layout == CnTradeDateLayout.TRADE_DATE_V2 && !(value instanceof LocalDate)) {
            Instant carrier;
            if (value instanceof Instant instant) carrier = instant;
            else if (value instanceof Timestamp timestamp) carrier = timestamp.toInstant();
            else if (value instanceof LocalDateTime local)
                carrier = local.toInstant(ZoneOffset.UTC);
            else if (value instanceof String text && text.length() == 10)
                return LocalDate.parse(text);
            else if (value instanceof String text) carrier = Instant.parse(text);
            else throw new IllegalArgumentException("L2 trade_date 日期载体类型不支持");
            return TemporalValues.CalendarTimestamp.fromStorage(carrier).date();
        }
        if (value instanceof LocalDate date) return date;
        if (value instanceof Instant instant) return instant.atZone(ZoneOffset.UTC).toLocalDate();
        if (value instanceof LocalDateTime dateTime) return dateTime.toLocalDate();
        if (value instanceof Timestamp timestamp)
            return timestamp.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        if (value instanceof String text) return LocalDate.parse(text.substring(0, 10));
        throw new IllegalArgumentException("Unsupported D086 business timestamp read type");
    }
}
