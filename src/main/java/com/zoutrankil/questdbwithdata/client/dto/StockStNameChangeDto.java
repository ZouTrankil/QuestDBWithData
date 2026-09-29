package com.zoutrankil.questdbwithdata.client.dto;

/** The four namechange fields used to derive daily ST state; dates use Tushare YYYYMMDD text. */
public record StockStNameChangeDto(String tsCode, String name, String startDate, String endDate) {}
