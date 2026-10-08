package com.zoutrankil.data.domain;

import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Index OHLC point data; updateTime is the frozen Java observation, not a Tushare field. */
public record IndexDailyMarket(IndexDailyMarketKey key, Double close, Double open, Double high,
        Double low, Double preClose, Double change, Double pctChg, Double vol, Double amount,
        Instant updateTime) {
    public IndexDailyMarket {
        Objects.requireNonNull(key, "complete index_daily_market key required");
        Objects.requireNonNull(updateTime, "frozen update_time required");
        TemporalValues.requirePrecision(updateTime, TemporalValues.Precision.MICROS);
        if (!finite(close) || !finite(open) || !finite(high) || !finite(low) || !finite(preClose)
                || !finite(change) || !finite(pctChg) || !finite(vol) || !finite(amount))
            throw new IllegalArgumentException("Index daily metrics must be finite or null");
    }
    private static boolean finite(Double value) { return value == null || Double.isFinite(value); }
    public IndexDailyMarket(String tsCode, LocalDate tradeDate, Double close, Double open, Double high,
            Double low, Double preClose, Double change, Double pctChg, Double vol, Double amount,
            Instant updateTime) {
        this(new IndexDailyMarketKey(tsCode, tradeDate), close, open, high, low, preClose,
                change, pctChg, vol, amount, updateTime);
    }
    public String tsCode() { return key.tsCode(); }
    public LocalDate tradeDate() { return key.tradeDate(); }
}
