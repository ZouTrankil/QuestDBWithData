package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Complete etf_daily identity, matching the audited QuestDB dedup key. */
public record EtfDailyKey(String tsCode, LocalDate tradeDate) {
    public EtfDailyKey {
        if (tsCode == null || !tsCode.matches("[0-9]{6}\\.(?:SH|SZ|OF)"))
            throw new IllegalArgumentException("Six-digit exchange-qualified Tushare ts_code required");
        Objects.requireNonNull(tradeDate, "trade_date required");
    }
}
