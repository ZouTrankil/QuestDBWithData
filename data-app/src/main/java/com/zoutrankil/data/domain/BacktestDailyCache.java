package com.zoutrankil.data.domain;

import java.util.Objects;

/** Python-owned, source-version-addressed backtest read-through cache row. */
public record BacktestDailyCache(BacktestDaily daily, String sourceVersion) {
    public BacktestDailyCache {
        Objects.requireNonNull(daily, "daily fields required");
        new BacktestDailyCacheKey(daily.tradeDate(), daily.tsCode(), sourceVersion);
    }

    public BacktestDailyCacheKey key() {
        return new BacktestDailyCacheKey(daily.tradeDate(), daily.tsCode(), sourceVersion);
    }
}
