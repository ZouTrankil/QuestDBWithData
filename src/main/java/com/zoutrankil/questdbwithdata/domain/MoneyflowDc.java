package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** D026 source values: monetary figures remain in Tushare's 10,000 CNY and rates in percent. */
public record MoneyflowDc(MoneyflowDcKey key, String name, Double pctChange, Double close,
        Double netAmount, Double netAmountRate, Double buyElgAmount, Double buyElgAmountRate,
        Double buyLgAmount, Double buyLgAmountRate, Double buyMdAmount, Double buyMdAmountRate,
        Double buySmAmount, Double buySmAmountRate) {
    public MoneyflowDc {
        Objects.requireNonNull(key, "complete moneyflow_dc business key required");
        for (Double value : new Double[]{pctChange, close, netAmount, netAmountRate, buyElgAmount,
                buyElgAmountRate, buyLgAmount, buyLgAmountRate, buyMdAmount, buyMdAmountRate,
                buySmAmount, buySmAmountRate}) {
            if (value != null && !Double.isFinite(value))
                throw new IllegalArgumentException("moneyflow_dc numeric values must be finite or null");
        }
    }
    public MoneyflowDc(String tsCode, LocalDate tradeDate, String name, Double pctChange, Double close,
            Double netAmount, Double netAmountRate, Double buyElgAmount, Double buyElgAmountRate,
            Double buyLgAmount, Double buyLgAmountRate, Double buyMdAmount, Double buyMdAmountRate,
            Double buySmAmount, Double buySmAmountRate) {
        this(new MoneyflowDcKey(tsCode, tradeDate), name, pctChange, close, netAmount, netAmountRate,
                buyElgAmount, buyElgAmountRate, buyLgAmount, buyLgAmountRate, buyMdAmount,
                buyMdAmountRate, buySmAmount, buySmAmountRate);
    }
    public String tsCode() { return key.tsCode(); }
    public LocalDate tradeDate() { return key.tradeDate(); }
}
