package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** The L2 daily business identity is the calendar date carried by ts plus symbol. */
public record L2DailyFeaturesKey(LocalDate tradeDate, String symbol) {
    public L2DailyFeaturesKey {
        Objects.requireNonNull(tradeDate, "trade date required");
        if (symbol == null || !symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)"))
            throw new IllegalArgumentException("Canonical L2 stock symbol required");
    }
}
