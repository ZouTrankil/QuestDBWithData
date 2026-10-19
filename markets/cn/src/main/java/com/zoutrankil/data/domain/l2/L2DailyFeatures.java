package com.zoutrankil.data.domain.l2;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 类型化业务键及固定字段映射，承载 108 个可为空的日频 L2 特征值。 */
public record L2DailyFeatures(LocalDate tradeDate, String symbol,
                              Map<L2DailyFeatureField, Object> features) {
    public L2DailyFeatures {
        Objects.requireNonNull(tradeDate, "L2 business date required");
        new L2DailyFeaturesKey(tradeDate, symbol);
        Objects.requireNonNull(features, "L2 feature values required");
        var normalized = new EnumMap<L2DailyFeatureField, Object>(L2DailyFeatureField.class);
        for (var field : L2DailyFeatureField.values()) {
            if (field.identity()) {
                if (features.containsKey(field))
                    throw new IllegalArgumentException("D086 identity fields are carried by the key: " + field.fieldName());
                continue;
            }
            if (!features.containsKey(field))
                throw new IllegalArgumentException("D086 feature field is missing: " + field.fieldName());
            normalized.put(field, field.normalize(features.get(field)));
        }
        if (features.size() != normalized.size())
            throw new IllegalArgumentException("D086 feature field set differs from the frozen schema");
        features = Collections.unmodifiableMap(normalized);
    }

    public L2DailyFeaturesKey key() { return new L2DailyFeaturesKey(tradeDate, symbol); }

    public Object feature(String name) {
        var field = L2DailyFeatureField.named(name);
        if (field == null || field.identity()) throw new IllegalArgumentException("Unknown D086 metric field");
        return features.get(field);
    }
}
