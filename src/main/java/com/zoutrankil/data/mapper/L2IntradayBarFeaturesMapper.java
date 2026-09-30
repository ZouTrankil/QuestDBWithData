package com.zoutrankil.data.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.L2IntradayBarFeaturesParquetDto;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.L2IntradayBarFeatureField;
import com.zoutrankil.data.domain.L2IntradayBarFeatures;
import com.zoutrankil.data.domain.L2IntradayBarFeaturesDataset;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;

/** Maps the 60 source Parquet columns to the frozen D087 domain and QuestDB schema. */
public final class L2IntradayBarFeaturesMapper {
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Shanghai");

    public L2IntradayBarFeatures fromParquet(JsonNode row, LocalDate expectedDate) throws IOException {
        var dto = L2IntradayBarFeaturesParquetDto.decode(row, expectedDate);
        Instant minute = TemporalValues.localInstant(dto.localMinute(), EXCHANGE_ZONE,
                TemporalValues.Precision.MICROS);
        return new L2IntradayBarFeatures(dto.tradeDate(), dto.symbol(), dto.market(), dto.board(),
                minute, dto.features());
    }

    public DatasetValues values(L2IntradayBarFeatures row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", row.tradeDate());
        values.put("symbol", row.symbol());
        values.put("market", row.market());
        values.put("board", row.board());
        values.put("minute", row.minute());
        for (var field : L2IntradayBarFeatureField.values())
            if (field.metric()) values.put(field.fieldName(), row.features().get(field));
        return new DatasetValues(values);
    }

    public L2IntradayBarFeatures fromValues(DatasetValues values) {
        Object rawDate = values.get("trade_date", Object.class);
        LocalDate day = rawDate instanceof LocalDate date ? date
                : TemporalValues.businessDate((String) rawDate, TemporalValues.DateFormat.BASIC);
        String symbol = values.get("symbol", String.class);
        String market = values.get("market", String.class);
        String board = values.get("board", String.class);
        Instant minute = storageInstant(values.get("minute", Object.class));
        var features = new EnumMap<L2IntradayBarFeatureField, Object>(L2IntradayBarFeatureField.class);
        for (var field : L2IntradayBarFeatureField.values())
            if (field.metric()) features.put(field, field.normalize(value(values, field)));
        return new L2IntradayBarFeatures(day, symbol, market, board, minute, features);
    }

    public static List<String> columns() {
        return L2IntradayBarFeaturesDataset.DEFINITION.columns().stream()
                .map(DatasetDefinition.Column::logicalName).toList();
    }

    private static Object value(DatasetValues values, L2IntradayBarFeatureField field) {
        return switch (field.storageType()) {
            case LONG -> values.get(field.fieldName(), Long.class);
            case DOUBLE -> values.get(field.fieldName(), Double.class);
            default -> throw new IllegalArgumentException("Unexpected D087 metric type: " + field.fieldName());
        };
    }

    private static Instant storageInstant(Object value) {
        if (value instanceof Long micros)
            return TemporalValues.epoch(micros, TemporalValues.EpochUnit.MICROS,
                    TemporalValues.Precision.MICROS);
        if (value instanceof Instant instant)
            return TemporalValues.requirePrecision(instant, TemporalValues.Precision.MICROS);
        if (value instanceof java.sql.Timestamp timestamp)
            return TemporalValues.requirePrecision(timestamp.toInstant(), TemporalValues.Precision.MICROS);
        if (value instanceof java.time.OffsetDateTime offset)
            return TemporalValues.requirePrecision(offset.toInstant(), TemporalValues.Precision.MICROS);
        if (value instanceof LocalDateTime local)
            return TemporalValues.localInstant(local, ZoneOffset.UTC, TemporalValues.Precision.MICROS);
        if (value instanceof String text) {
            try { return TemporalValues.requirePrecision(Instant.parse(text), TemporalValues.Precision.MICROS); }
            catch (java.time.format.DateTimeParseException ignored) {
                return TemporalValues.localInstant(LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        ZoneOffset.UTC, TemporalValues.Precision.MICROS);
            }
        }
        throw new IllegalArgumentException("Unsupported D087 QuestDB timestamp read type");
    }
}
