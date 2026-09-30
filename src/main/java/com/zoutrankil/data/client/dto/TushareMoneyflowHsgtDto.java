package com.zoutrankil.data.client.dto;

/** Raw scalar fields from one Tushare moneyflow_hsgt response row. */
public record TushareMoneyflowHsgtDto(String tradeDate, String ggtSs, String ggtSz,
        String hgt, String sgt, String northMoney, String southMoney) {}
