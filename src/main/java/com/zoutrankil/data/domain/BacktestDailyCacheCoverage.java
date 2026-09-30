package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Read model for Python's integrity receipt, published only after the matching cache slice verifies.
 * The Java migration deliberately has no writer for this owner-controlled assertion.
 */
public record BacktestDailyCacheCoverage(
        LocalDate tradeDate,
        String sourceVersion,
        long rowCount,
        String contentDigest) {
    public BacktestDailyCacheCoverage {
        var key = new BacktestDailyCacheCoverageKey(tradeDate, sourceVersion);
        Objects.requireNonNull(key);
        if (rowCount < 1) throw new IllegalArgumentException("Published cache coverage must contain at least one row");
        if (contentDigest == null || !contentDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Lowercase SHA-256 content_digest required");
        }
    }

    public BacktestDailyCacheCoverageKey key() {
        return new BacktestDailyCacheCoverageKey(tradeDate, sourceVersion);
    }
}
