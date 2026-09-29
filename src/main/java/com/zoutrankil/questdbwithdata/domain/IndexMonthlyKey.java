package com.zoutrankil.questdbwithdata.domain;

import com.zoutrankil.questdbwithdata.service.IndexMonthlyUniverse;
import java.time.LocalDate;
import java.util.Objects;

/** Complete monthly business identity: the historical provider-compatible code and its month observation day. */
public record IndexMonthlyKey(String tsCode, LocalDate tradeDate) {
    public IndexMonthlyKey {
        if (!IndexMonthlyUniverse.validProviderCode(tsCode))
            throw new IllegalArgumentException("Known frozen monthly index code required");
        Objects.requireNonNull(tradeDate, "trade_date required");
    }
}
