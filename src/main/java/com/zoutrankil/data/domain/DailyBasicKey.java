package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Complete daily_basic identity; both instrument and trade date participate. */
public record DailyBasicKey(String tsCode, LocalDate tradeDate) {
    public DailyBasicKey {
        if (tsCode == null || !tsCode.matches("[0-9]{6}\\.(SZ|SH|BJ)"))
            throw new IllegalArgumentException("Valid Tushare stock code required");
        Objects.requireNonNull(tradeDate, "trade date required");
    }
}
