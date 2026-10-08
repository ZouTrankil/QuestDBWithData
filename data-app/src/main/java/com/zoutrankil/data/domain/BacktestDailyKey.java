package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Complete compatibility identity of one exchange calendar date and A-share instrument. */
public record BacktestDailyKey(LocalDate tradeDate, String tsCode) {
    public BacktestDailyKey {
        Objects.requireNonNull(tradeDate, "trade_date required");
        if (tsCode == null || !tsCode.matches("[0-9]{6}\\.(?:SH|SZ|BJ)")) {
            throw new IllegalArgumentException("Six-digit exchange-qualified A-share ts_code required");
        }
    }
}
