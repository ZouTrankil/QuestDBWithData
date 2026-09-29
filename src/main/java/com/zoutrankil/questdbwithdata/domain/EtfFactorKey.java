package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Complete physical/business identity: fund code plus provider trade date. */
public record EtfFactorKey(String tsCode, LocalDate tradeDate) {
    public EtfFactorKey {
        if (tsCode == null || !tsCode.matches("[0-9]{6}\\.[A-Z]{2}"))
            throw new IllegalArgumentException("Six-digit exchange-qualified fund code required");
        Objects.requireNonNull(tradeDate, "trade_date required");
    }
}
