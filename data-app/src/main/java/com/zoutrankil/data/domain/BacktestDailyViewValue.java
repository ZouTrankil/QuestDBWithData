package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Typed business row of the current Python-authored backtest view. */
public record BacktestDailyViewValue(
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
        Long isSuspended,
        Integer isSt) {

    public BacktestDailyViewValue {
        Objects.requireNonNull(tradeDate, "trade_date required");
        new BacktestDailyKey(tradeDate, tsCode);
        for (var value : new Double[]{open, high, low, close, vol, amount, adjFactor, upLimit, downLimit}) {
            if (value != null && !Double.isFinite(value))
                throw new IllegalArgumentException("Backtest view numeric value must be finite or null");
        }
    }

    public BacktestDailyKey key() { return new BacktestDailyKey(tradeDate, tsCode); }
}
