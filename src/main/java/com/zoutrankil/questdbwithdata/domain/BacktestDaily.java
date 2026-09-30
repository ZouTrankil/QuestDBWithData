package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Typed read model for the retained Python backtest_daily snapshot.
 * The current Python read-through product remains the computation/cache owner.
 */
public record BacktestDaily(
        LocalDate tradeDate,
        String tsCode,
        Double open,
        Double high,
        Double low,
        Double close,
        Double vol,
        Double amount,
        Double adjFactor,
        Double upLimit,
        Double downLimit,
        Integer isSuspended,
        Integer isSt) {

    public BacktestDaily {
        Objects.requireNonNull(tradeDate, "trade_date required");
        new BacktestDailyKey(tradeDate, tsCode);
        for (var value : new Double[]{open, high, low, close, vol, amount, adjFactor, upLimit, downLimit}) {
            if (value != null && !Double.isFinite(value)) {
                throw new IllegalArgumentException("backtest_daily numeric values must be finite or null");
            }
        }
    }

    public BacktestDailyKey key() { return new BacktestDailyKey(tradeDate, tsCode); }
}
