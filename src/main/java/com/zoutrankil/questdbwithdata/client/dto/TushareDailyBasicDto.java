package com.zoutrankil.questdbwithdata.client.dto;

/** Raw scalar values from one Tushare daily_basic response row. */
public record TushareDailyBasicDto(
        String tsCode,
        String tradeDate,
        String close,
        String turnoverRate,
        String turnoverRateF,
        String volumeRatio,
        String pe,
        String peTtm,
        String pb,
        String ps,
        String psTtm,
        String dvRatio,
        String dvTtm,
        String totalShare,
        String floatShare,
        String freeShare,
        String totalMv,
        String circMv) {}
