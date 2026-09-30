package com.zoutrankil.data.domain;

import java.time.LocalDate;

/** Owner-published integrity receipt for one versioned market barometer cache record. */
public record MarketBarometerCacheCoverage(
        LocalDate tradeDate,
        String datasetId,
        String sourceVersion,
        long rowCount,
        String contentDigest) {
    public MarketBarometerCacheCoverage {
        new MarketBarometerCacheCoverageKey(tradeDate, datasetId, sourceVersion);
        if (rowCount < 0 || rowCount > 1)
            throw new IllegalArgumentException("Daily barometer coverage row_count must be zero or one");
        if (contentDigest == null || !contentDigest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Lowercase SHA-256 content_digest required");
    }

    public MarketBarometerCacheCoverageKey key() {
        return new MarketBarometerCacheCoverageKey(tradeDate, datasetId, sourceVersion);
    }
}
