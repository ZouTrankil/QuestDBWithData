package com.zoutrankil.questdbwithdata.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Typed D087 minute observation; its trade date is the Asia/Shanghai date of its UTC minute. */
public record L2IntradayBarFeatures(LocalDate tradeDate, String symbol, String market, String board,
                                    Instant minute,
                                    Map<L2IntradayBarFeatureField, Object> features) {
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Shanghai");

    public L2IntradayBarFeatures {
        Objects.requireNonNull(tradeDate, "D087 trade date required");
        var key = new L2IntradayBarFeaturesKey(symbol, minute);
        if (!tradeDate.equals(minute.atZone(EXCHANGE_ZONE).toLocalDate()))
            throw new IllegalArgumentException("D087 local minute date differs from trade_date");
        if (market == null || !market.matches("SH|SZ|BJ") || !market.equals(symbol.substring(7)))
            throw new IllegalArgumentException("D087 market must agree with the canonical symbol suffix");
        if (board == null || board.isBlank() || board.length() > 32)
            throw new IllegalArgumentException("D087 board category is required");
        Objects.requireNonNull(features, "D087 feature map required");
        var normalized = new EnumMap<L2IntradayBarFeatureField, Object>(L2IntradayBarFeatureField.class);
        for (var field : L2IntradayBarFeatureField.values()) {
            if (!field.metric()) {
                if (features.containsKey(field))
                    throw new IllegalArgumentException("D087 identity field belongs to the row: " + field.fieldName());
                continue;
            }
            if (!features.containsKey(field))
                throw new IllegalArgumentException("D087 metric field is missing: " + field.fieldName());
            normalized.put(field, field.normalize(features.get(field)));
        }
        if (features.size() != normalized.size())
            throw new IllegalArgumentException("D087 feature field set differs from the frozen schema");
        features = Collections.unmodifiableMap(normalized);
    }

    public L2IntradayBarFeaturesKey key() { return new L2IntradayBarFeaturesKey(symbol, minute); }

    public Object feature(String name) {
        var field = L2IntradayBarFeatureField.named(name);
        if (field == null || !field.metric()) throw new IllegalArgumentException("Unknown D087 metric field");
        return features.get(field);
    }
}
