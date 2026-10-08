package com.zoutrankil.data.client.dto;

/** Raw scalar DTO for the documented Tushare moneyflow_ths response fields. */
public record TushareMoneyflowThsDto(String tsCode, String tradeDate, String name, String pctChange,
        String latest, String netAmount, String netD5Amount, String buyLgAmount, String buyLgAmountRate,
        String buyMdAmount, String buyMdAmountRate, String buySmAmount, String buySmAmountRate) {}
