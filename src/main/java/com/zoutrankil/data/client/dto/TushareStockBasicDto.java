package com.zoutrankil.data.client.dto;

/** Wire-level Tushare fields; date values keep their source representation here. */
public record TushareStockBasicDto(
        String tsCode,
        String symbol,
        String name,
        String area,
        String industry,
        String listDate) {}
