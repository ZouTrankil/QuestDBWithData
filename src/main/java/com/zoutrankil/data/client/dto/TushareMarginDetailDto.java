package com.zoutrankil.data.client.dto;

/** Raw scalar projection of the exact Tushare margin_detail response fields. */
public record TushareMarginDetailDto(String tradeDate, String tsCode, String name, String rzye, String rzmre,
        String rzche, String rqye, String rqyl, String rqchl, String rqmcl, String rzrqye) {}
