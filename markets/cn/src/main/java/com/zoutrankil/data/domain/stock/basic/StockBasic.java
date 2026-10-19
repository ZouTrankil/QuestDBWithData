package com.zoutrankil.data.domain.stock.basic;

import java.time.LocalDate;

public record StockBasic(
        String tsCode,
        String symbol,
        String name,
        String area,
        String industry,
        LocalDate listDate) {
    public StockBasicKey key() {
        return new StockBasicKey(tsCode);
    }
}
