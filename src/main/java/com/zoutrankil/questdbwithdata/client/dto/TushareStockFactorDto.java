package com.zoutrankil.questdbwithdata.client.dto;

import com.zoutrankil.questdbwithdata.domain.StockFactorFields;

/** Source-shaped legacy stk_factor values; source pct_change matches the physical column name. */
public record TushareStockFactorDto(String tsCode, String tradeDate, StockFactorFields fields) {}
