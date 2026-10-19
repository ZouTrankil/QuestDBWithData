package com.zoutrankil.data.client.dto;

/** 对应 Tushare 原始报文字段；日期在此层保留来源格式。 */
public record TushareStockBasicDto(
        String tsCode,
        String symbol,
        String name,
        String area,
        String industry,
        String listDate) {
    public static final java.util.List<String> FIELDS = java.util.List.of("ts_code","symbol","name","area","industry","list_date");
}
