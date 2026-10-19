package com.zoutrankil.data.domain;

import java.time.LocalDate;

/** Direct daily share/daily INNER JOIN aggregate; shares are in ten-thousand-share units. */
public record EtfMarketOverviewDailyView(LocalDate tradeDate, long etfCount,
                                       Double totalShare, Double totalSizeYi) {
    public EtfMarketOverviewDailyView {
        new EtfMarketOverviewDailyViewKey(tradeDate);
        if (etfCount < 0) throw new IllegalArgumentException("Nonnegative exact ETF count required");
        if ((totalShare != null && !Double.isFinite(totalShare))
                || (totalSizeYi != null && !Double.isFinite(totalSizeYi)))
            throw new IllegalArgumentException("Finite ETF overview aggregates or null required");
    }

    public EtfMarketOverviewDailyViewKey key() {
        return new EtfMarketOverviewDailyViewKey(tradeDate);
    }
}
