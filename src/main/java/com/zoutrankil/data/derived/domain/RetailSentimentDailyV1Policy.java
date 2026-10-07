package com.zoutrankil.data.derived.domain;

import com.zoutrankil.data.domain.RetailSentimentDailyV1;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Pure native aggregation identity, finite bounds and row comparison. */
public final class RetailSentimentDailyV1Policy {
    public static final String SOURCE = "l2_daily_features";
    public static final String OUTPUT = "mv_retail_sentiment_daily_v1";
    public static final int MAX_WINDOW_DAYS = 31;
    public static final long MAX_SOURCE_ROWS = 200_000;
    private RetailSentimentDailyV1Policy() {}

    public static boolean equivalent(RetailSentimentDailyV1 left, RetailSentimentDailyV1 right) {
        return left != null && right != null && left.tradeDate().equals(right.tradeDate())
                && Objects.equals(left.totalQ1(), right.totalQ1()) && Objects.equals(left.totalQ3(), right.totalQ3())
                && Objects.equals(left.totalSpoofCount(), right.totalSpoofCount())
                && Objects.equals(left.totalManipulationCount(), right.totalManipulationCount())
                && close(left.avgRetailRatio(), right.avgRetailRatio())
                && close(left.avgRetailEntropy(), right.avgRetailEntropy())
                && close(left.totalRetailAmountYi(), right.totalRetailAmountYi())
                && close(left.totalRetailNetInflowYi(), right.totalRetailNetInflowYi())
                && close(left.avgRelAggro(), right.avgRelAggro())
                && close(left.avgWashTradeRatio(), right.avgWashTradeRatio())
                && close(left.avgMfiScore(), right.avgMfiScore())
                && close(left.totalMainNetYi(), right.totalMainNetYi());
    }
    private static boolean close(Double expected, Double actual) {
        if (expected == null || actual == null) return expected == actual;
        return Double.isFinite(expected) && Double.isFinite(actual)
                && Math.abs(actual - expected) <= 1e-8 + 1e-10 * Math.abs(expected);
    }
    public static void window(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)
                || ChronoUnit.DAYS.between(start, end) + 1 > MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("D098 requires an explicit nonempty window of at most 31 days");
    }
}
