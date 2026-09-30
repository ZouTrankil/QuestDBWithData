package com.zoutrankil.questdbwithdata.domain;

import java.util.Objects;

/** Tushare market-wide moneyflow_hsgt observation; metrics remain in provider million-CNY units. */
public record MoneyflowHsgt(MoneyflowHsgtKey key, Double ggtSs, Double ggtSz, Double hgt,
        Double sgt, Double northMoney, Double southMoney) {
    public MoneyflowHsgt {
        Objects.requireNonNull(key, "D027 natural key required");
        for (Double value : new Double[]{ggtSs, ggtSz, hgt, sgt, northMoney, southMoney})
            if (value != null && !Double.isFinite(value))
                throw new IllegalArgumentException("D027 values must be finite doubles or null");
    }
    public java.time.LocalDate tradeDate() { return key.tradeDate(); }
}
