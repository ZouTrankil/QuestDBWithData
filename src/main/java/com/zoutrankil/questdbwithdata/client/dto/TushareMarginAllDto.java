package com.zoutrankil.questdbwithdata.client.dto;

/** Exact nine-field response contract for Tushare margin(trade_date=...). */
public record TushareMarginAllDto(String tradeDate, String exchangeId, String rzye, String rzmre,
        String rzche, String rqye, String rqmcl, String rzrqye, String rqyl) {}
