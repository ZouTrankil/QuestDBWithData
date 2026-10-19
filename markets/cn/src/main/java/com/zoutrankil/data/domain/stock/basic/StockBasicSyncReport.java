package com.zoutrankil.data.domain.stock.basic;

import java.time.Instant;

public record StockBasicSyncReport(
        int submittedRows,
        long visibleRows,
        Instant snapshotTimestamp) {}
