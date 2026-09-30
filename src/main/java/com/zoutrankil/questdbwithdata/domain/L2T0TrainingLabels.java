package com.zoutrankil.questdbwithdata.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Typed D089 minute labels; future-return and alpha fields are look-ahead outcomes. */
public record L2T0TrainingLabels(LocalDate tradeDate, String symbol, String market, String board,
                                 Instant minute, Map<L2T0TrainingLabelField, Object> labels) {
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Shanghai");

    public L2T0TrainingLabels {
        Objects.requireNonNull(tradeDate, "D089 trade date required");
        new L2T0TrainingLabelsKey(symbol, minute);
        if (!tradeDate.equals(minute.atZone(EXCHANGE_ZONE).toLocalDate()))
            throw new IllegalArgumentException("D089 local minute date differs from trade_date");
        if (market == null || !market.matches("SH|SZ|BJ") || !market.equals(symbol.substring(7)))
            throw new IllegalArgumentException("D089 market must agree with the canonical symbol suffix");
        if (board == null || board.isBlank() || board.length() > 32)
            throw new IllegalArgumentException("D089 board category is required");
        Objects.requireNonNull(labels, "D089 label map required");
        var normalized = new EnumMap<L2T0TrainingLabelField, Object>(L2T0TrainingLabelField.class);
        for (var field : L2T0TrainingLabelField.values()) {
            if (!field.metric()) {
                if (labels.containsKey(field))
                    throw new IllegalArgumentException("D089 identity field belongs to the row: " + field.fieldName());
                continue;
            }
            if (!labels.containsKey(field))
                throw new IllegalArgumentException("D089 label field is missing: " + field.fieldName());
            normalized.put(field, field.normalize(labels.get(field)));
        }
        if (labels.size() != normalized.size())
            throw new IllegalArgumentException("D089 label field set differs from the frozen schema");
        labels = Collections.unmodifiableMap(normalized);
    }

    public L2T0TrainingLabelsKey key() { return new L2T0TrainingLabelsKey(symbol, minute); }

    public Object label(String name) {
        var field = L2T0TrainingLabelField.named(name);
        if (field == null || !field.metric()) throw new IllegalArgumentException("Unknown D089 label field");
        return labels.get(field);
    }
}
