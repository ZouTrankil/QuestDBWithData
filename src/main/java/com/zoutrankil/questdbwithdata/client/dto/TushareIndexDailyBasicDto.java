package com.zoutrankil.questdbwithdata.client.dto;

/** Raw index_dailybasic response fields; numeric values retain Tushare's native units. */
public record TushareIndexDailyBasicDto(String tsCode, String tradeDate, Double totalMv,
        Double floatMv, Double totalShare, Double floatShare, Double freeShare,
        Double turnoverRate, Double turnoverRateF, Double pe, Double peTtm, Double pb) {}
