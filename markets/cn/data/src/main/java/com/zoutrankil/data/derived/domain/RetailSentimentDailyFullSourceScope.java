package com.zoutrankil.data.derived.domain;

import java.time.LocalDate;

public record RetailSentimentDailyFullSourceScope(LocalDate from, LocalDate to, long rawRows) {
    public RetailSentimentDailyFullSourceScope {
        RetailSentimentDailyPolicy.window(from, to);
        if (rawRows < 1 || rawRows > RetailSentimentDailyPolicy.MAX_SOURCE_ROWS)
            throw new IllegalArgumentException(
                    "D098 FULL requires nonempty finite complete source rows");
    }
}
