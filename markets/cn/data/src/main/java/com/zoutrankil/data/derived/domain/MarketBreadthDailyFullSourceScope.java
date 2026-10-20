package com.zoutrankil.data.derived.domain;

import java.time.LocalDate;

public record MarketBreadthDailyFullSourceScope(LocalDate from, LocalDate to, long rawRows) {
    public MarketBreadthDailyFullSourceScope {
        MarketBreadthDailyPolicy.window(from, to);
        if (rawRows < 1 || rawRows > MarketBreadthDailyPolicy.MAX_SOURCE_ROWS)
            throw new IllegalArgumentException(
                    "D095 FULL requires nonempty finite complete source rows");
    }
}
