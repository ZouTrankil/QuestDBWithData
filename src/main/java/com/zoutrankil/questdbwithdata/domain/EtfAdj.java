package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;

/** Tushare fund adjustment factor, without rescaling; null remains null. */
public record EtfAdj(EtfAdjKey key, Double adjFactor) {
    public EtfAdj {
        if (key == null) throw new IllegalArgumentException("Complete ETF adjustment key required");
        if (adjFactor != null && !Double.isFinite(adjFactor))
            throw new IllegalArgumentException("ETF adjustment factor must be finite or null");
    }
    public EtfAdj(String tsCode, LocalDate tradeDate, Double adjFactor) {
        this(new EtfAdjKey(tsCode, tradeDate), adjFactor);
    }
    public String tsCode() { return key.tsCode(); }
    public LocalDate tradeDate() { return key.tradeDate(); }
}
