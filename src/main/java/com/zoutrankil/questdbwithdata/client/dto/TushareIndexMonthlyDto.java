package com.zoutrankil.questdbwithdata.client.dto;

/** Exact Tushare index_monthly response fields before adding the frozen research taxonomy. */
public record TushareIndexMonthlyDto(String tsCode, String tradeDate, Double close, Double open,
        Double high, Double low, Double preClose, Double change, Double pctChg, Double vol, Double amount) {}
