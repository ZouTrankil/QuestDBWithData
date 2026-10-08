package com.zoutrankil.data.client.dto;

/** Raw scalar fields from one Tushare margin_secs response row. */
public record TushareMarginSecsDto(String tradeDate, String tsCode, String name, String exchange) {}
