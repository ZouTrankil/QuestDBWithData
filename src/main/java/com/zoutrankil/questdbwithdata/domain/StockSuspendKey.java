package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Full natural identity for the audited physical UPSERT KEY (ts_code,timestamp). */
public record StockSuspendKey(String tsCode, LocalDate tradeDate) {
    public StockSuspendKey {
        if (tsCode == null || !tsCode.matches("[0-9]{6}\\.(?:SZ|SH|BJ)"))
            throw new IllegalArgumentException("Tushare A-share code with exchange suffix required");
        Objects.requireNonNull(tradeDate, "suspension business date required");
    }
}
