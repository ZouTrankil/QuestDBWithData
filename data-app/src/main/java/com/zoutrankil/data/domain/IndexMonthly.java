package com.zoutrankil.data.domain;

import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Monthly index observation; tradeDate is the provider's month-end trading day, updateTime is Java observation metadata. */
public record IndexMonthly(IndexMonthlyKey key, Double close, Double open, Double high, Double low,
        Double preClose, Double change, Double pctChg, Double vol, Double amount,
        String layer, String bucket, Instant updateTime) {
    public IndexMonthly {
        Objects.requireNonNull(key, "complete index_monthly key required");
        if (layer == null || layer.isBlank() || bucket == null || bucket.isBlank())
            throw new IllegalArgumentException("Frozen layer and bucket are required");
        Objects.requireNonNull(updateTime, "frozen update_time required");
        TemporalValues.requirePrecision(updateTime, TemporalValues.Precision.MICROS);
        if (!finite(close) || !finite(open) || !finite(high) || !finite(low) || !finite(preClose)
                || !finite(change) || !finite(pctChg) || !finite(vol) || !finite(amount))
            throw new IllegalArgumentException("Monthly index metrics must be finite or null");
    }
    private static boolean finite(Double value) { return value == null || Double.isFinite(value); }
    public IndexMonthly(String tsCode, LocalDate tradeDate, Double close, Double open, Double high, Double low,
            Double preClose, Double change, Double pctChg, Double vol, Double amount,
            String layer, String bucket, Instant updateTime) {
        this(new IndexMonthlyKey(tsCode, tradeDate), close, open, high, low, preClose, change,
                pctChg, vol, amount, layer, bucket, updateTime);
    }
    public String tsCode() { return key.tsCode(); }
    public LocalDate tradeDate() { return key.tradeDate(); }
}
