package com.zoutrankil.data.client.dto;

/** Raw stk_limit API scalars. Tushare dates remain text until strict mapper validation. */
public record TushareStockLimitDto(String tsCode, String tradeDate, Double upLimit, Double downLimit) {}
