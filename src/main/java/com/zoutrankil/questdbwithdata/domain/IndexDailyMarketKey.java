package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Complete business identity of one index observation. */
public record IndexDailyMarketKey(String tsCode, LocalDate tradeDate) {
    public IndexDailyMarketKey {
        if (tsCode == null || !tsCode.matches("[0-9]{6}\\.(?:SH|SZ|CSI|SI)"))
            throw new IllegalArgumentException("Exchange-qualified index ts_code required");
        Objects.requireNonNull(tradeDate, "trade_date required");
    }
}
