package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Existing directory source is snapshot-only. Activation belongs to the verified runner. */
public final class StockBasicJobDefinition {
    private StockBasicJobDefinition() {}

    public static final SyncJobDefinition CURRENT = StockBasicSyncAdapter.definition(true);
    public static final SyncJobDefinition DEFINITION =
            new SyncJobDefinition(
                    "data.stock_basic",
                    1,
                    "stock_basic_snapshot",
                    1,
                    "StockBasicSyncService",
                    Set.of(Mode.SNAPSHOT),
                    Mode.SNAPSHOT,
                    Map.of(),
                    "tushare.shared",
                    "stock_basic.snapshot",
                    "questdb.full_key_values",
                    new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)),
                    Duration.ofMinutes(5),
                    new Budget(1, 1, 1, 10000, 1024 * 1024),
                    0,
                    List.of(),
                    Frequency.MANUAL,
                    ZoneId.of("Asia/Shanghai"),
                    false,
                    false);
}
