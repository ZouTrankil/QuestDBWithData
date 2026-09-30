package com.zoutrankil.data.client.dto;

/** Exact fund_adj response projection; trade_date remains a business date. */
public record TushareEtfAdjDto(String tsCode, String tradeDate, Double adjFactor) {}
