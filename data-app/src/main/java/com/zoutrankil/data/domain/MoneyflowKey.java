package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Full business/upsert key for one A-share moneyflow observation. */
public record MoneyflowKey(String tsCode, LocalDate tradeDate) {
    public MoneyflowKey {
        Objects.requireNonNull(tsCode, "moneyflow ts_code required");
        Objects.requireNonNull(tradeDate, "moneyflow trade_date required");
        if (!tsCode.matches("[0-9]{6}\\.(SZ|SH|BJ)"))
            throw new IllegalArgumentException("moneyflow requires a mainland SZ/SH/BJ A-share code");
    }
}
