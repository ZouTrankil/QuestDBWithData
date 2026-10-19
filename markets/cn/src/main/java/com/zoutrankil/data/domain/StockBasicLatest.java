package com.zoutrankil.data.domain;

import java.time.Instant;
import java.time.LocalDate;

/** Database-independent result contract for the latest stock-directory row per tsCode. */
public record StockBasicLatest(
        Instant snapshotTimestamp,
        String tsCode,
        String symbol,
        String name,
        String area,
        String industry,
        LocalDate listDate) {}
