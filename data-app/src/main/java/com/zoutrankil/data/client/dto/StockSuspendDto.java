package com.zoutrankil.data.client.dto;

/** Full source row returned by suspend_d; suspend_timing is retained for evidence but is not stored. */
public record StockSuspendDto(String tsCode, String tradeDate, String suspendTiming, String suspendType) {
    public StockSuspendDto {
        if (tsCode == null || tsCode.isBlank() || tradeDate == null || tradeDate.isBlank())
            throw new IllegalArgumentException("suspend_d code and date are required");
        if (!"S".equals(suspendType)) throw new IllegalArgumentException("Only suspension (S) rows are in scope");
    }
}
