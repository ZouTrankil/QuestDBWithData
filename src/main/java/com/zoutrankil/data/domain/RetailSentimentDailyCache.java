package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Historical Python-published generation; four required LONG counts retain their exact values. */
public record RetailSentimentDailyCache(LocalDate tradeDate, Double avgRetailRatio, Double avgRetailEntropy,
        Double totalRetailAmountYi, Double totalRetailNetInflowYi, Double avgRelAggro,
        long totalQ1, long totalQ3, Double avgWashTradeRatio, long totalSpoofCount,
        long totalManipulationCount, Double avgMfiScore, Double totalMainNetYi, String sourceVersion) {
    public RetailSentimentDailyCache {
        Objects.requireNonNull(tradeDate, "trade date required");
        new RetailSentimentDailyCacheKey(tradeDate, sourceVersion);
        for (var value : new Double[]{avgRetailRatio, avgRetailEntropy, totalRetailAmountYi,
                totalRetailNetInflowYi, avgRelAggro, avgWashTradeRatio, avgMfiScore, totalMainNetYi}) {
            if (value != null && !Double.isFinite(value)) throw new IllegalArgumentException("Finite retail cache aggregates required");
        }
        if (totalQ1 < 0 || totalQ3 < 0 || totalSpoofCount < 0 || totalManipulationCount < 0)
            throw new IllegalArgumentException("Nonnegative required retail cache counts required");
    }

    public RetailSentimentDailyCacheKey key() { return new RetailSentimentDailyCacheKey(tradeDate, sourceVersion); }
}
