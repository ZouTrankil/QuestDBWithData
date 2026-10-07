package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Date and complete Python source generation identify one historical cache row. */
public record MarketBreadthDailyCacheKey(LocalDate tradeDate, String sourceVersion) {
    public MarketBreadthDailyCacheKey {
        Objects.requireNonNull(tradeDate, "trade date required");
        requireVersion(sourceVersion);
    }

    public static void requireVersion(String sourceVersion) {
        if (sourceVersion == null || !sourceVersion.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Lowercase SHA-256 source_version required");
    }
}
