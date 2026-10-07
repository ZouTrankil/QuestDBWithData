package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Public daily alias of the native breadth MV; aggregate values pass through unchanged. */
public record MarketBreadthDailyView(LocalDate tradeDate, long stockCount, long upCount,
                                     long downCount, long flatCount, Double avgPctChange,
                                     Double totalAmountYi) {
    public MarketBreadthDailyView {
        Objects.requireNonNull(tradeDate, "trade date required");
        // Subtraction avoids overflowing the sum when invalid external counts approach LONG_MAX.
        if (stockCount < 1 || upCount < 0 || downCount < 0 || flatCount < 0
                || upCount > stockCount || downCount > stockCount - upCount
                || flatCount > stockCount - upCount - downCount) {
            throw new IllegalArgumentException("Invalid daily market breadth counts");
        }
        if (avgPctChange != null && !Double.isFinite(avgPctChange)
                || totalAmountYi != null && !Double.isFinite(totalAmountYi)) {
            throw new IllegalArgumentException("Finite aggregates required");
        }
    }
}
