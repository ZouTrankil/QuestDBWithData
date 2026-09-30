package com.zoutrankil.questdbwithdata.domain;

import java.time.Instant;
import java.util.Objects;

/** Complete D089 physical UPSERT identity: stock symbol and UTC minute. */
public record L2T0TrainingLabelsKey(String symbol, Instant minute) {
    public L2T0TrainingLabelsKey {
        if (symbol == null || !symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)"))
            throw new IllegalArgumentException("Canonical D089 stock symbol required");
        Objects.requireNonNull(minute, "D089 minute instant required");
        if (minute.getNano() != 0 || Math.floorMod(minute.getEpochSecond(), 60) != 0)
            throw new IllegalArgumentException("D089 key must be aligned to a whole minute");
    }
}
