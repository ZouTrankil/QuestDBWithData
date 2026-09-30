package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Set;
import java.util.Objects;

/** Complete identity of one daily basic observation for one frozen core index. */
public record IndexDailyBasicKey(String tsCode, LocalDate tradeDate) {
    public IndexDailyBasicKey {
        if (!Set.of("000300.SH", "000016.SH", "399006.SZ", "000905.SH", "000852.SH").contains(tsCode))
            throw new IllegalArgumentException("D020 core index ts_code required");
        Objects.requireNonNull(tradeDate, "trade_date required");
    }
}
