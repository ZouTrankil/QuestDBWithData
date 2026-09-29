package com.zoutrankil.questdbwithdata.client.dto;

/** Wire row for index_daily/sw_daily after the endpoint-specific pct field is normalized. */
public record TushareIndexDailyDto(String tsCode, String tradeDate, Double close, Double open,
        Double high, Double low, Double preClose, Double change, Double pctChg, Double vol,
        Double amount) {}
