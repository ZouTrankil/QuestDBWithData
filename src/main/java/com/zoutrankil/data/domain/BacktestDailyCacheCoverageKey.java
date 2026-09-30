package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Exact versioned identity of one Python-published cache coverage receipt. */
public record BacktestDailyCacheCoverageKey(LocalDate tradeDate, String sourceVersion) {
    public BacktestDailyCacheCoverageKey {
        Objects.requireNonNull(tradeDate, "trade_date required");
        if (sourceVersion == null || !sourceVersion.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Lowercase SHA-256 source_version required");
        }
    }
}
