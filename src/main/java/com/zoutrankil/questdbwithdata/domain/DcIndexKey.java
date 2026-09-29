package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Complete daily board identity; physical QuestDB DEDUP remains disabled. */
public record DcIndexKey(String tsCode, LocalDate tradeDate) {
    public DcIndexKey {
        if (tsCode == null || !tsCode.matches("[A-Z0-9_]+\\.DC"))
            throw new IllegalArgumentException("DC-qualified concept code required");
        Objects.requireNonNull(tradeDate, "trade_date required");
    }
}
