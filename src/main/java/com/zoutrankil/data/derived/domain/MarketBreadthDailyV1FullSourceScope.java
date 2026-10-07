package com.zoutrankil.data.derived.domain;

import java.time.LocalDate;

public record MarketBreadthDailyV1FullSourceScope(LocalDate from, LocalDate to, long rawRows) {
        public MarketBreadthDailyV1FullSourceScope {
            MarketBreadthDailyV1Policy.window(from, to);
            if (rawRows < 1 || rawRows > MarketBreadthDailyV1Policy.MAX_SOURCE_ROWS)
                throw new IllegalArgumentException("D095 FULL requires nonempty finite complete source rows");
        }
    }
