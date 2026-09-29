package com.zoutrankil.questdbwithdata.client.dto;

/** Raw typed projection of the documented Tushare moneyflow response. */
public record TushareMoneyflowDto(String tsCode, String tradeDate,
        String buySmVol, String buySmAmount, String sellSmVol, String sellSmAmount,
        String buyMdVol, String buyMdAmount, String sellMdVol, String sellMdAmount,
        String buyLgVol, String buyLgAmount, String sellLgVol, String sellLgAmount,
        String buyElgVol, String buyElgAmount, String sellElgVol, String sellElgAmount,
        String netMfVol, String netMfAmount) {}
