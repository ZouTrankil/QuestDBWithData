package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Unscaled Tushare ETF technical indicators; null means the source returned no value. */
public record EtfFactor(EtfFactorKey key, Map<String, Double> factors) {
    public EtfFactor {
        Objects.requireNonNull(key, "Complete etf_factor key required");
        if (factors == null || !factors.keySet().equals(SetHolder.FIELDS))
            throw new IllegalArgumentException("Every frozen etf_factor field, and no others, is required");
        var copy = new LinkedHashMap<String, Double>();
        for (String field : EtfFactorDataset.NUMERIC_FIELDS) {
            Double value = factors.get(field);
            if (value != null && !Double.isFinite(value))
                throw new IllegalArgumentException("Non-finite etf_factor value: " + field);
            copy.put(field, value);
        }
        factors = Collections.unmodifiableMap(copy);
    }
    public EtfFactor(String tsCode, LocalDate tradeDate, Map<String, Double> factors) {
        this(new EtfFactorKey(tsCode, tradeDate), factors);
    }
    public String tsCode() { return key.tsCode(); }
    public LocalDate tradeDate() { return key.tradeDate(); }

    private static final class SetHolder {
        private static final java.util.Set<String> FIELDS = java.util.Set.copyOf(EtfFactorDataset.NUMERIC_FIELDS);
    }
}
