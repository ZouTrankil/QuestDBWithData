package com.zoutrankil.data.client.dto;

/** Explicit fund_share provider fields, including nullable fund_type and provider market. */
public record TushareEtfShareDto(String tsCode, String tradeDate, Double fdShare, String fundType, String market) {}
