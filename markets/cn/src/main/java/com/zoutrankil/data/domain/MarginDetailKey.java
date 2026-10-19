package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Natural/upsert identity for one security's margin record on one trade date. */
public record MarginDetailKey(String tsCode, LocalDate tradeDate) {
    public MarginDetailKey {
        if (tsCode == null || !tsCode.matches("[0-9]{6}\\.(SH|SZ|BJ)"))
            throw new IllegalArgumentException("margin_detail ts_code must be a mainland security code");
        Objects.requireNonNull(tradeDate, "margin_detail trade_date required");
    }
}
