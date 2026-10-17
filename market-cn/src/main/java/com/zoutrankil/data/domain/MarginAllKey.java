package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Natural identity is one exchange aggregate for one Tushare trade date. */
public record MarginAllKey(LocalDate tradeDate, String exchangeId) {
    public MarginAllKey {
        Objects.requireNonNull(tradeDate, "D028 trade_date required");
        if (!java.util.Set.of("SSE", "SZSE", "BSE").contains(exchangeId))
            throw new IllegalArgumentException("D028 exchange_id must be SSE, SZSE or BSE");
    }
}
