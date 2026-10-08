package com.zoutrankil.data.domain;

import java.util.Objects;

/** Tushare margin aggregate; values remain in the provider's native units. */
public record MarginAll(MarginAllKey key, Double rzye, Double rzmre, Double rzche, Double rqye,
        Double rqmcl, Double rzrqye, Double rqyl) {
    public MarginAll {
        Objects.requireNonNull(key, "D028 complete natural key required");
        for (Double value : new Double[]{rzye, rzmre, rzche, rqye, rqmcl, rzrqye, rqyl})
            if (value == null || !Double.isFinite(value))
                throw new IllegalArgumentException("D028 source numeric columns must be present and finite");
    }
    public java.time.LocalDate tradeDate() { return key.tradeDate(); }
    public String exchangeId() { return key.exchangeId(); }
}
