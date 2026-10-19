package com.zoutrankil.data.domain.stock.basic;

import java.time.Instant;

/** Composite identity matching QuestDB's UPSERT KEYS(snapshot_ts, ts_code). */
public record StockBasicSnapshotKey(Instant snapshotTimestamp, String tsCode) {}
