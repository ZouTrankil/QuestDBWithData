package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Complete date and original Python source-generation SHA identify one cache row. */
public record EtfMarketOverviewDailyCacheKey(LocalDate tradeDate, String sourceVersion) {
    public EtfMarketOverviewDailyCacheKey {
        Objects.requireNonNull(tradeDate, "trade date required");
        requireVersion(sourceVersion);
    }

    public static void requireVersion(String sourceVersion) {
        if (sourceVersion == null || !sourceVersion.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Complete lowercase SHA-256 source_version required");
    }
}
