package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** D027 natural identity: one Shanghai/Hong Kong Connect aggregate per provider trade date. */
public record MoneyflowHsgtKey(LocalDate tradeDate) {
    public MoneyflowHsgtKey { Objects.requireNonNull(tradeDate, "D027 trade_date required"); }
}
