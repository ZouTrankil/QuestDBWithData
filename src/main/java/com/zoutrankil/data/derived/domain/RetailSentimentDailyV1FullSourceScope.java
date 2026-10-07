package com.zoutrankil.data.derived.domain;

import java.time.LocalDate;

public record RetailSentimentDailyV1FullSourceScope(LocalDate from, LocalDate to, long rawRows) {
        public RetailSentimentDailyV1FullSourceScope {
            RetailSentimentDailyV1Policy.window(from, to);
            if (rawRows < 1 || rawRows > RetailSentimentDailyV1Policy.MAX_SOURCE_ROWS)
                throw new IllegalArgumentException("D098 FULL requires nonempty finite complete source rows");
        }
    }
