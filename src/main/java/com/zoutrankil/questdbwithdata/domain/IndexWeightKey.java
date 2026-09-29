package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Full identity of an index constituent weight observation. */
public record IndexWeightKey(String indexCode, String conCode, LocalDate tradeDate) {
    public IndexWeightKey {
        if (indexCode == null || !indexCode.matches("[0-9]{6}"))
            throw new IllegalArgumentException("Six digit canonical index_code required");
        if (conCode == null || !conCode.matches("[0-9]{6}\\.[A-Z]{2,4}"))
            throw new IllegalArgumentException("Exchange-qualified six digit constituent code required");
        Objects.requireNonNull(tradeDate, "trade_date required");
    }
}
