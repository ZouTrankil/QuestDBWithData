package com.zoutrankil.data.domain;

import java.time.LocalDate;

/** One original Python-published ETF overview generation; shares are in ten-thousand-share units. */
public record EtfMarketOverviewDailyCache(LocalDate tradeDate, long etfCount, Double totalShare,
                                        Double totalSizeYi, String sourceVersion) {
    public EtfMarketOverviewDailyCache {
        new EtfMarketOverviewDailyCacheKey(tradeDate, sourceVersion);
        if (etfCount < 0) throw new IllegalArgumentException("Nonnegative exact ETF count required");
        if ((totalShare != null && !Double.isFinite(totalShare))
                || (totalSizeYi != null && !Double.isFinite(totalSizeYi)))
            throw new IllegalArgumentException("Finite ETF overview aggregates or null required");
    }

    public EtfMarketOverviewDailyCacheKey key() {
        return new EtfMarketOverviewDailyCacheKey(tradeDate, sourceVersion);
    }
}
