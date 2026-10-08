package com.zoutrankil.data.client.dto;

/** Wire dates remain exact YYYYMMDD text until explicitly mapped. */
public record TushareTradeCalendarDto(String exchange, String calDate, Integer isOpen, String pretradeDate) {}
