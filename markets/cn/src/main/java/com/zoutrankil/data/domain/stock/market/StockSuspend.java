package com.zoutrankil.data.domain.stock.market;

import java.time.LocalDate;
import java.util.Objects;

/** A provider S record means a suspension applies on this date; it does not mean a full-day halt. */
public record StockSuspend(StockSuspendKey key, long isSuspended) {
    public StockSuspend {
        Objects.requireNonNull(key, "complete suspension key required");
        if (isSuspended != 1L)
            throw new IllegalArgumentException("suspend_d S records normalize to LONG 1");
    }

    public String tsCode() {
        return key.tsCode();
    }

    public LocalDate tradeDate() {
        return key.tradeDate();
    }
}
