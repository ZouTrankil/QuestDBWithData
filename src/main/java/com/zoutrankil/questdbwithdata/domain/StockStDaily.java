package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;

/** Positive daily ST membership. A missing key denotes no ST record, matching the audited table contract. */
public record StockStDaily(StockStDailyKey key, int isSt) {
    public StockStDaily {
        if (key == null) throw new IllegalArgumentException("Complete stk_st_daily key required");
        if (isSt != 1) throw new IllegalArgumentException("stk_st_daily stores positive ST membership rows only");
    }
    public StockStDaily(String tsCode, LocalDate timestamp) { this(new StockStDailyKey(tsCode, timestamp), 1); }
    public StockStDaily(String tsCode, LocalDate timestamp, int isSt) {
        this(new StockStDailyKey(tsCode, timestamp), isSt);
    }
    public String tsCode() { return key.tsCode(); }
    public LocalDate timestamp() { return key.timestamp(); }
}
