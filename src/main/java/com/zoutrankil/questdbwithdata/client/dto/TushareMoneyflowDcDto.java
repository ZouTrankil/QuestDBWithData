package com.zoutrankil.questdbwithdata.client.dto;

/** Raw typed projection for the Tushare moneyflow_dc response. */
public record TushareMoneyflowDcDto(String tsCode, String tradeDate, String name,
        String pctChange, String close, String netAmount, String netAmountRate,
        String buyElgAmount, String buyElgAmountRate, String buyLgAmount, String buyLgAmountRate,
        String buyMdAmount, String buyMdAmountRate, String buySmAmount, String buySmAmountRate) {}
