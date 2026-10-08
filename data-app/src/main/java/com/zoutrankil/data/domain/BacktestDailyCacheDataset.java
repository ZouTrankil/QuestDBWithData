package com.zoutrankil.data.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D092 read-only contract for the Python-owned versioned read-through cache. */
public final class BacktestDailyCacheDataset {
    private BacktestDailyCacheDataset() {}

    private static List<Column> columns() {
        var result = new ArrayList<>(BacktestDailyDataset.DEFINITION.columns());
        result.add(new Column("source_version", "source_version", "source_version", StorageType.SYMBOL, false,
                "SHA-256 fingerprint of the view definition and upstream source partition state", null));
        return List.copyOf(result);
    }

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "backtest_daily_cache", 1, "python.derived.backtest_daily_cache",
            "python.BacktestReadThroughCache._publish", "backtest_daily_cache", ObjectKind.TABLE,
            columns(), List.of("trade_date", "ts_code", "source_version"),
            List.of("trade_date", "ts_code", "source_version"), "trade_date", Partition.MONTH, true,
            Set.of(Capability.READ), List.of(),
            "Preserve Python's MONTH/WAL/DEDUP cache and complete versioned key. Python reads stk_factor, "
                    + "stk_limit, stk_suspend and stk_st_daily through v_backtest_daily, then writes a slice, "
                    + "verifies WAL-visible row count and content, then publishes the coverage receipt. "
                    + "Java reads only, so it cannot create an unreceipted or competing cache generation.");
}
