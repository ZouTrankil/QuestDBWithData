package com.zoutrankil.data.domain;

import java.util.Objects;

/** One Tushare moneyflow_ths observation. Amounts/rates remain in provider units. */
public record MoneyflowThs(MoneyflowThsKey key, String name, Double pctChange, Double latest,
        Double netAmount, Double netD5Amount, Double buyLgAmount, Double buyLgAmountRate,
        Double buyMdAmount, Double buyMdAmountRate, Double buySmAmount, Double buySmAmountRate) {
    public MoneyflowThs {
        Objects.requireNonNull(key, "Complete D025 business key required");
        for (Double number : new Double[]{pctChange, latest, netAmount, netD5Amount, buyLgAmount,
                buyLgAmountRate, buyMdAmount, buyMdAmountRate, buySmAmount, buySmAmountRate}) {
            if (number != null && !Double.isFinite(number))
                throw new IllegalArgumentException("D025 metrics must be finite doubles or null");
        }
    }
    public String tsCode() { return key.tsCode(); }
    public java.time.LocalDate tradeDate() { return key.tradeDate(); }
}
