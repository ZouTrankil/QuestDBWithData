package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import java.time.*;
import java.util.*;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** Existing directory source is snapshot-only. Activation belongs to the verified runner. */
public final class StockBasicJobDefinition {
    private StockBasicJobDefinition() {}
    public static final SyncJobDefinition CURRENT = StockBasicSyncAdapter.definition(true);
    public static final SyncJobDefinition DEFINITION = new SyncJobDefinition(
            "data.stock_basic", 1, "stock_basic_snapshot", 1, "StockBasicSyncService",
            Set.of(Mode.SNAPSHOT), Mode.SNAPSHOT, Map.of(), "tushare.shared", "stock_basic.snapshot",
            "questdb.full_key_values", new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)),
            Duration.ofMinutes(5), new Budget(1, 1, 1, 10000, 1024 * 1024), 0,
            List.of(), Frequency.MANUAL, ZoneId.of("Asia/Shanghai"), false, false);
}
