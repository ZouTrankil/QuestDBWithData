package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Complete fund_adj business identity, mapped to physical (ts_code,timestamp). */
public record EtfAdjKey(String tsCode, LocalDate tradeDate) {
    public EtfAdjKey {
        if (tsCode == null || !tsCode.matches("[0-9]{6}\\.(?:SH|SZ|OF)"))
            throw new IllegalArgumentException("Six-digit exchange-qualified Tushare fund code required");
        Objects.requireNonNull(tradeDate, "trade_date required");
    }
}
