package com.zoutrankil.data.client.dto;
/** Explicit eleven-field fund_daily wire contract. */
public record TushareEtfDailyDto(String tsCode,String tradeDate,Double preClose, Double open, Double high, Double low, Double close, Double change, Double pctChg, Double vol, Double amount) {}
