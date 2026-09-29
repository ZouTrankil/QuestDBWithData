package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;

/** Daily upper/lower price limits; values are copied in Tushare currency-per-share units. */
public record StockLimit(StockLimitKey key, Double upLimit, Double downLimit) {
    public StockLimit {
        if (key == null) throw new IllegalArgumentException("Complete stk_limit key required");
        if (upLimit != null && !Double.isFinite(upLimit) || downLimit != null && !Double.isFinite(downLimit))
            throw new IllegalArgumentException("stk_limit values must be finite or null");
    }
    public StockLimit(String tsCode, LocalDate tradeDate, Double upLimit, Double downLimit) {
        this(new StockLimitKey(tsCode, tradeDate), upLimit, downLimit);
    }
    public String tsCode() { return key.tsCode(); }
    public LocalDate tradeDate() { return key.tradeDate(); }
}
