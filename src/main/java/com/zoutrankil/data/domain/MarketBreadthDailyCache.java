package com.zoutrankil.data.domain;

import java.time.LocalDate;

/** Historical daily aggregate published by the Python read-through owner, with its exact generation. */
public record MarketBreadthDailyCache(LocalDate tradeDate, long stockCount, long upCount,
                                      long downCount, long flatCount, Double avgPctChange,
                                      Double totalAmountYi, String sourceVersion) {
    public MarketBreadthDailyCache {
        new MarketBreadthDailyView(tradeDate, stockCount, upCount, downCount, flatCount,
                avgPctChange, totalAmountYi);
        new MarketBreadthDailyCacheKey(tradeDate, sourceVersion);
    }

    public MarketBreadthDailyCacheKey key() {
        return new MarketBreadthDailyCacheKey(tradeDate, sourceVersion);
    }
}
