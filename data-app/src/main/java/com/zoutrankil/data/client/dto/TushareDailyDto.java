package com.zoutrankil.data.client.dto;

/** The thirteen fields requested from Tushare's unadjusted A-share daily endpoint. */
public record TushareDailyDto(
        String tsCode,
        String tradeDate,
        Double open,
        Double high,
        Double low,
        Double close,
        Double preClose,
        Double change,
        Double pctChg,
        Double vol,
        Double amount,
        Double ahVol,
        Double ahAmount) {}
