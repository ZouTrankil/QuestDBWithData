package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** One calendar-day native aggregation of l2_daily_features; SQL nulls remain null. */
public record RetailSentimentDailyV1(
        LocalDate tradeDate,
        Double avgRetailRatio,
        Double avgRetailEntropy,
        Double totalRetailAmountYi,
        Double totalRetailNetInflowYi,
        Double avgRelAggro,
        Long totalQ1,
        Long totalQ3,
        Double avgWashTradeRatio,
        Long totalSpoofCount,
        Long totalManipulationCount,
        Double avgMfiScore,
        Double totalMainNetYi) {
    public RetailSentimentDailyV1 {
        Objects.requireNonNull(tradeDate, "trade date required");
        for (var value : new Double[]{avgRetailRatio, avgRetailEntropy, totalRetailAmountYi,
                totalRetailNetInflowYi, avgRelAggro, avgWashTradeRatio, avgMfiScore, totalMainNetYi}) {
            if (value != null && !Double.isFinite(value)) {
                throw new IllegalArgumentException("Finite retail sentiment aggregates required");
            }
        }
        requireCount(totalQ1, "total_q1");
        requireCount(totalQ3, "total_q3");
        requireCount(totalSpoofCount, "total_spoof_count");
        requireCount(totalManipulationCount, "total_manipulation_count");
    }

    private static void requireCount(Long value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException("Nonnegative nullable count required: " + field);
        }
    }
}
