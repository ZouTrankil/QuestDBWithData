package com.zoutrankil.data.domain;

import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** One source-observed index membership weight; updateTime is the frozen capture instant. */
public record IndexWeight(IndexWeightKey key, String indexName, String indexNameEn,
        String conName, String conNameEn, String exchange, String exchangeEn,
        Double weight, Instant updateTime) {
    public IndexWeight {
        Objects.requireNonNull(key, "Complete index_weight key required");
        if (weight == null || !Double.isFinite(weight) || weight < 0.0 || weight > 100.0)
            throw new IllegalArgumentException("Finite index weight percentage in [0,100] required");
        Objects.requireNonNull(updateTime, "Frozen update_time required");
        TemporalValues.requirePrecision(updateTime, TemporalValues.Precision.MICROS);
    }
    public IndexWeight(String indexCode, String conCode, LocalDate tradeDate,
            String indexName, String indexNameEn, String conName, String conNameEn,
            String exchange, String exchangeEn, Double weight, Instant updateTime) {
        this(new IndexWeightKey(indexCode, conCode, tradeDate), indexName, indexNameEn,
                conName, conNameEn, exchange, exchangeEn, weight, updateTime);
    }
    public String indexCode() { return key.indexCode(); }
    public String conCode() { return key.conCode(); }
    public LocalDate tradeDate() { return key.tradeDate(); }
}
