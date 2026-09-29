package com.zoutrankil.questdbwithdata.domain;

import java.util.Objects;

/** One daily factor observation. Volume and amount remain in source units (shares in lots, amount in kRMB). */
public record StockFactor(StockFactorKey key, StockFactorFields fields) {
    public StockFactor {
        Objects.requireNonNull(key, "complete stock factor key required");
        Objects.requireNonNull(fields, "factor field group required");
    }
    public String tsCode() { return key.tsCode(); }
    public java.time.LocalDate tradeDate() { return key.tradeDate(); }
}
