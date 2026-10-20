package com.zoutrankil.data.domain.derived;

import java.time.LocalDate;
import java.util.Objects;

/** One calendar-day aggregation of stk_factor, owned by the native QuestDB MV. */
public record MarketBreadthDaily(
        LocalDate tradeDate,
        long stockCount,
        long upCount,
        long downCount,
        long flatCount,
        Double avgPctChange,
        Double totalAmountYi) {
    public MarketBreadthDaily {
        Objects.requireNonNull(tradeDate, "trade date required");
        if (stockCount < 1
                || upCount < 0
                || downCount < 0
                || flatCount < 0
                || upCount + downCount + flatCount > stockCount) {
            throw new IllegalArgumentException("Invalid daily market breadth counts");
        }
        if (avgPctChange != null && !Double.isFinite(avgPctChange)
                || totalAmountYi != null && !Double.isFinite(totalAmountYi)) {
            throw new IllegalArgumentException("Finite aggregates required");
        }
    }
}
