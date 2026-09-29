package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Physical and business identity is the complete instrument plus exchange calendar day. */
public record StockStDailyKey(String tsCode, LocalDate timestamp) {
    public StockStDailyKey {
        if (tsCode == null || !tsCode.matches("[0-9]{6}\\.(?:SH|SZ|BJ)"))
            throw new IllegalArgumentException("Mainland stock code required");
        Objects.requireNonNull(timestamp, "Calendar timestamp required");
    }
}
