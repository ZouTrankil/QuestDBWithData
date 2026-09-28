package com.zoutrankil.questdbwithdata.domain;

import java.time.Instant;

public record StockBasicSyncReport(
        int submittedRows,
        long visibleRows,
        Instant snapshotTimestamp) {}
