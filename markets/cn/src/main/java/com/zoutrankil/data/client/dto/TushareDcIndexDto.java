package com.zoutrankil.data.client.dto;

/** Exact Python dc_index response projection. Numeric source values are not scaled. */
public record TushareDcIndexDto(String tsCode, String tradeDate, String name, String leading,
        String leadingCode, Double pctChange, Double leadingPct, Double totalMv,
        Double turnoverRate, Integer upNum, Integer downNum) {}
