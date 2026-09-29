package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Complete stk_limit identity, matching the audited QuestDB dedup key. */
public record StockLimitKey(String tsCode, LocalDate tradeDate) {
    public StockLimitKey {
        if (tsCode == null || !tsCode.matches("[0-9]{6}\\.(?:SH|SZ|BJ)"))
            throw new IllegalArgumentException("Six-digit exchange-qualified Tushare ts_code required");
        Objects.requireNonNull(tradeDate, "trade_date required");
    }
}
