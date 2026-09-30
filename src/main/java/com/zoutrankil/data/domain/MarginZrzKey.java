package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Python/model physical identity: exactly one summary row for a trade date. */
public record MarginZrzKey(LocalDate tradeDate) {
    public MarginZrzKey { Objects.requireNonNull(tradeDate, "D031 trade_date required"); }
}
