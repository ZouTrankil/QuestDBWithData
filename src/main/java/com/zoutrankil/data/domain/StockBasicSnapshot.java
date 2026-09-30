package com.zoutrankil.data.domain;

import java.time.Instant;

/** A point-in-time stock directory row. */
public record StockBasicSnapshot(Instant snapshotTimestamp, StockBasic stock) {
    public StockBasicSnapshotKey key() {
        return new StockBasicSnapshotKey(snapshotTimestamp, stock.tsCode());
    }
}
