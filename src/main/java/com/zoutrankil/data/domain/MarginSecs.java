package com.zoutrankil.data.domain;

import java.util.Objects;

/** Daily margin-financing eligible security membership; name may be absent in the source. */
public record MarginSecs(MarginSecsKey key, String name, String exchange) {
    public MarginSecs {
        Objects.requireNonNull(key, "D030 natural key required");
        if (exchange == null || exchange.isBlank() || !exchange.equals(exchange.strip()))
            throw new IllegalArgumentException("D030 exchange is required and must be trimmed");
    }
    public java.time.LocalDate tradeDate() { return key.tradeDate(); }
    public String tsCode() { return key.tsCode(); }
}
