package com.zoutrankil.data.domain;

import java.util.Objects;

/** One Tushare slb_len daily transfer-financing summary; nullable provider metrics stay null. */
public record MarginZrz(MarginZrzKey key, Double ob, Double aucAmount, Double repoAmount,
        Double repayAmount, Double cb) {
    public MarginZrz {
        Objects.requireNonNull(key, "D031 trade_date natural key required");
        for (Double value : new Double[]{ob, aucAmount, repoAmount, repayAmount, cb})
            if (value != null && !Double.isFinite(value))
                throw new IllegalArgumentException("D031 numeric source values must be finite when present");
    }
    public java.time.LocalDate tradeDate() { return key.tradeDate(); }
}
