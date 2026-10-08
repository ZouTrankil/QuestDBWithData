package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Complete versioned identity of one market barometer cache receipt. */
public record MarketBarometerCacheCoverageKey(LocalDate tradeDate, String datasetId, String sourceVersion) {
    public MarketBarometerCacheCoverageKey {
        Objects.requireNonNull(tradeDate, "trade_date required");
        if (datasetId == null || !datasetId.matches("[a-z][a-z0-9_]{1,127}"))
            throw new IllegalArgumentException("Canonical dataset_id required");
        if (sourceVersion == null || !sourceVersion.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Lowercase SHA-256 source_version required");
    }
}
