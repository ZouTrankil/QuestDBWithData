package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Natural and audited physical identity for one eligible security on one trading day. */
public record MarginSecsKey(LocalDate tradeDate, String tsCode) {
    public MarginSecsKey {
        Objects.requireNonNull(tradeDate, "D030 trade_date required");
        if (tsCode == null || !tsCode.matches("[0-9]{6}\\.(?:SH|SZ|BJ)"))
            throw new IllegalArgumentException("D030 ts_code must be a six-digit SH/SZ/BJ code");
    }
}
