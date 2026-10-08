package com.zoutrankil.data.derived.domain;

import com.zoutrankil.data.domain.MarketBreadthDailyV1;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Pure native aggregation identity, finite bounds and row comparison. */
public final class MarketBreadthDailyV1Policy {
    public static final String SOURCE = "stk_factor";
    public static final String OUTPUT = "mv_market_breadth_daily_v1";
    public static final int MAX_WINDOW_DAYS = 31;
    public static final long MAX_SOURCE_ROWS = 200_000;
    private MarketBreadthDailyV1Policy() {}

    public static boolean equivalent(MarketBreadthDailyV1 left, MarketBreadthDailyV1 right) {
        return left != null && right != null && left.tradeDate().equals(right.tradeDate())
                && left.stockCount() == right.stockCount() && left.upCount() == right.upCount()
                && left.downCount() == right.downCount() && left.flatCount() == right.flatCount()
                && close(left.avgPctChange(), right.avgPctChange()) && close(left.totalAmountYi(), right.totalAmountYi());
    }
    private static boolean close(Double expected, Double actual) {
        if (expected == null || actual == null) return expected == actual;
        return Double.isFinite(expected) && Double.isFinite(actual)
                && Math.abs(actual - expected) <= 1e-8 + 1e-10 * Math.abs(expected);
    }
    public static void window(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)
                || ChronoUnit.DAYS.between(start, end) + 1 > MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("D095 requires an explicit nonempty window of at most 31 days");
    }
}
