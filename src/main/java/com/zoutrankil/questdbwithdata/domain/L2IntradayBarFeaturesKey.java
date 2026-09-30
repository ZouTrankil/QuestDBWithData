package com.zoutrankil.questdbwithdata.domain;

import java.time.Instant;
import java.util.Objects;

/** Full D087 business and QuestDB UPSERT identity: stock symbol plus UTC minute instant. */
public record L2IntradayBarFeaturesKey(String symbol, Instant minute) {
    public L2IntradayBarFeaturesKey {
        if (symbol == null || !symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)"))
            throw new IllegalArgumentException("Canonical D087 stock symbol required");
        Objects.requireNonNull(minute, "D087 minute instant required");
        if (minute.getNano() != 0 || Math.floorMod(minute.getEpochSecond(), 60) != 0)
            throw new IllegalArgumentException("D087 key must be aligned to a whole minute");
    }
}
