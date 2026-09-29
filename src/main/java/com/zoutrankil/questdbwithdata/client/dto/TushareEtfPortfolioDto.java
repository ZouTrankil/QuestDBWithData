package com.zoutrankil.questdbwithdata.client.dto;

/** Raw typed projection of the nine declared fund_portfolio fields. */
public record TushareEtfPortfolioDto(
        String tsCode, String annDate, String endDate, String symbol,
        Double marketValue, Double amount, Double stockMarketValueRatio,
        Double stockFloatRatio) {}
