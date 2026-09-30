package com.zoutrankil.questdbwithdata.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Typed D088 event row; future response fields are outcomes and are unavailable at event time. */
public record L2EventResponseFeatures(LocalDate tradeDate, String symbol, String market, String board,
                                      Instant minute, String eventType,
                                      Map<L2EventResponseFeatureField, Object> features) {
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Shanghai");

    public L2EventResponseFeatures {
        Objects.requireNonNull(tradeDate, "D088 trade date required");
        new L2EventResponseFeaturesKey(symbol, minute, eventType);
        if (!tradeDate.equals(minute.atZone(EXCHANGE_ZONE).toLocalDate()))
            throw new IllegalArgumentException("D088 local minute date differs from trade_date");
        if (market == null || !market.matches("SH|SZ|BJ") || !market.equals(symbol.substring(7)))
            throw new IllegalArgumentException("D088 market must agree with the canonical symbol suffix");
        if (board == null || board.isBlank() || board.length() > 32)
            throw new IllegalArgumentException("D088 board category is required");
        Objects.requireNonNull(features, "D088 feature map required");
        var normalized = new EnumMap<L2EventResponseFeatureField, Object>(L2EventResponseFeatureField.class);
        for (var field : L2EventResponseFeatureField.values()) {
            if (!field.metric()) {
                if (features.containsKey(field))
                    throw new IllegalArgumentException("D088 identity field belongs to the row: " + field.fieldName());
                continue;
            }
            if (!features.containsKey(field))
                throw new IllegalArgumentException("D088 metric field is missing: " + field.fieldName());
            normalized.put(field, field.normalize(features.get(field)));
        }
        if (features.size() != normalized.size())
            throw new IllegalArgumentException("D088 feature field set differs from the frozen schema");
        features = Collections.unmodifiableMap(normalized);
    }

    public L2EventResponseFeaturesKey key() { return new L2EventResponseFeaturesKey(symbol, minute, eventType); }

    public Object feature(String name) {
        var field = L2EventResponseFeatureField.named(name);
        if (field == null || !field.metric()) throw new IllegalArgumentException("Unknown D088 metric field");
        return features.get(field);
    }
}
