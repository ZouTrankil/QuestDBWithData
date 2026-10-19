package com.zoutrankil.data.client.dto;

/** Canonicalized source fields shared by the CSI XLS and Tushare index_weight routes. */
public record IndexWeightSourceDto(String indexCode, String conCode, String tradeDate,
        String indexName, String indexNameEn, String conName, String conNameEn,
        String exchange, String exchangeEn, Double weight) {}
