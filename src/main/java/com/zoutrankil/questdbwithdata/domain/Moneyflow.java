package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** D024 moneyflow row: flow volumes in hands; amounts in 10,000 CNY, without rescaling. */
public record Moneyflow(MoneyflowKey key,
        Long buySmVol, Double buySmAmount, Long sellSmVol, Double sellSmAmount,
        Long buyMdVol, Double buyMdAmount, Long sellMdVol, Double sellMdAmount,
        Long buyLgVol, Double buyLgAmount, Long sellLgVol, Double sellLgAmount,
        Long buyElgVol, Double buyElgAmount, Long sellElgVol, Double sellElgAmount,
        Long netMfVol, Double netMfAmount) {
    public Moneyflow {
        Objects.requireNonNull(key, "complete moneyflow key required");
        for (Long volume : new Long[]{buySmVol,sellSmVol,buyMdVol,sellMdVol,buyLgVol,sellLgVol,buyElgVol,sellElgVol}) {
            if (volume == null || volume < 0) throw new IllegalArgumentException("Moneyflow buy/sell volumes must be nonnegative; source nulls are normalized to zero");
        }
        if (netMfVol == null) throw new IllegalArgumentException("Python-compatible net_mf_vol normalization required");
        for (Double amount : new Double[]{buySmAmount,sellSmAmount,buyMdAmount,sellMdAmount,buyLgAmount,sellLgAmount,buyElgAmount,sellElgAmount,netMfAmount})
            if (amount != null && !Double.isFinite(amount)) throw new IllegalArgumentException("Moneyflow amounts must be finite or null");
    }
    public Moneyflow(String tsCode, LocalDate tradeDate, Long buySmVol, Double buySmAmount, Long sellSmVol, Double sellSmAmount,
            Long buyMdVol, Double buyMdAmount, Long sellMdVol, Double sellMdAmount, Long buyLgVol, Double buyLgAmount,
            Long sellLgVol, Double sellLgAmount, Long buyElgVol, Double buyElgAmount, Long sellElgVol, Double sellElgAmount,
            Long netMfVol, Double netMfAmount) {
        this(new MoneyflowKey(tsCode, tradeDate), buySmVol, buySmAmount, sellSmVol, sellSmAmount,
                buyMdVol, buyMdAmount, sellMdVol, sellMdAmount, buyLgVol, buyLgAmount, sellLgVol, sellLgAmount,
                buyElgVol, buyElgAmount, sellElgVol, sellElgAmount, netMfVol, netMfAmount);
    }
    public String tsCode() { return key.tsCode(); }
    public LocalDate tradeDate() { return key.tradeDate(); }
}
