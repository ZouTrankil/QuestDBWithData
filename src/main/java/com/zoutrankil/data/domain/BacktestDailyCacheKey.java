package com.zoutrankil.data.domain;

import java.time.LocalDate;

/** Complete versioned identity of one backtest daily cache row. */
public record BacktestDailyCacheKey(LocalDate tradeDate, String tsCode, String sourceVersion) {
    public BacktestDailyCacheKey {
        new BacktestDailyKey(tradeDate, tsCode);
        new BacktestDailyCacheCoverageKey(tradeDate, sourceVersion);
    }
}
