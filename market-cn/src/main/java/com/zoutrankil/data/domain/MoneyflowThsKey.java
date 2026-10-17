package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Full D025 business and QuestDB UPSERT key: one stock for one trade date. */
public record MoneyflowThsKey(String tsCode, LocalDate tradeDate) {
    public MoneyflowThsKey {
        Objects.requireNonNull(tsCode, "D025 ts_code required");
        Objects.requireNonNull(tradeDate, "D025 trade_date required");
        if (!tsCode.matches("[0-9]{6}\\.(?:SH|SZ|BJ)"))
            throw new IllegalArgumentException("D025 requires a six-digit Tushare exchange-qualified stock code");
    }
}
