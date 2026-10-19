package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Full source identity for one fund's daily share snapshot. */
public record EtfShareKey(String tsCode, LocalDate tradeDate) {
    public EtfShareKey {
        if (tsCode == null || !tsCode.matches("[0-9]{6}\\.(?:SH|SZ|OF)"))
            throw new IllegalArgumentException("Six-digit SH/SZ/OF Tushare fund code required");
        Objects.requireNonNull(tradeDate, "trade_date required");
    }
}
