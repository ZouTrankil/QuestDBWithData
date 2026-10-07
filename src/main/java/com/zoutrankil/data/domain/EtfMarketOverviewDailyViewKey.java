package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Natural identity of one direct calendar-day aggregate; the view has no stored generation key. */
public record EtfMarketOverviewDailyViewKey(LocalDate tradeDate) {
    public EtfMarketOverviewDailyViewKey {
        Objects.requireNonNull(tradeDate, "trade date required");
    }
}
