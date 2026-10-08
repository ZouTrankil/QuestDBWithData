package com.zoutrankil.data.client.dto;

/** Complete requested stock_basic fields; date strings are validated by the business mapper. */
public record TushareStockDetailDto(String tsCode,String symbol,String name,String area,String industry,
        String fullname,String enname,String cnspell,String market,String exchange,String currType,
        String listStatus,String listDate,String delistDate,String isHs,String actName,String actEntType) {}
