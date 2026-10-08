package com.zoutrankil.data.client.dto;

import com.zoutrankil.data.domain.StockFactorFields;

/** Source-shaped legacy stk_factor values; source pct_change matches the physical column name. */
public record TushareStockFactorDto(String tsCode, String tradeDate, StockFactorFields fields) {}
