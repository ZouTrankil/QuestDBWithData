package com.zoutrankil.data.domain;

import java.time.Instant;

public record StockBasicSyncReport(
        int submittedRows,
        long visibleRows,
        Instant snapshotTimestamp) {}
