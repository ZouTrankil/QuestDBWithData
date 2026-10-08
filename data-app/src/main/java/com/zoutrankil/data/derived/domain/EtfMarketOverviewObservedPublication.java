package com.zoutrankil.data.derived.domain;

import com.zoutrankil.data.domain.EtfMarketOverviewDailyCache;
import com.zoutrankil.data.domain.MarketBarometerCacheCoverage;
import java.util.List;

/** Stable physical readback; duplicates are retained for application rejection. */
public record EtfMarketOverviewObservedPublication(List<EtfMarketOverviewDailyCache> cache,
        List<MarketBarometerCacheCoverage> receipts) {
    public EtfMarketOverviewObservedPublication {
        cache = List.copyOf(cache);
        receipts = List.copyOf(receipts);
    }
}
