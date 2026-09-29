package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Full stock and calendar-day identity used by the source, reader and physical dedup key. */
public record StockFactorKey(String tsCode, LocalDate tradeDate) {
    public StockFactorKey {
        if (tsCode == null || !tsCode.matches("[0-9]{6}\\.(?:SZ|SH|BJ)"))
            throw new IllegalArgumentException("Tushare A-share code with exchange suffix required");
        Objects.requireNonNull(tradeDate, "trade date required");
    }
}
