package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Full daily identity for one Eastmoney money-flow observation. */
public record MoneyflowDcKey(String tsCode, LocalDate tradeDate) {
    public MoneyflowDcKey {
        Objects.requireNonNull(tsCode, "moneyflow_dc ts_code required");
        Objects.requireNonNull(tradeDate, "moneyflow_dc trade_date required");
        if (!tsCode.matches("[0-9]{6}\\.(SZ|SH|BJ)"))
            throw new IllegalArgumentException("moneyflow_dc requires an SZ/SH/BJ stock code");
    }
}
