package com.zoutrankil.data.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Public true native MV; its original name and all thirteen typed business fields are preserved. */
public final class BacktestDailyViewDataset {
    private BacktestDailyViewDataset() {}

    private static List<Column> columns() {
        var result = new ArrayList<Column>();
        for (var column : BacktestDailyDataset.DEFINITION.columns()) {
            if (column.storageName().equals("is_suspended")) {
                result.add(new Column("derived:stk_suspend", "is_suspended", "is_suspended", StorageType.LONG,
                        true, "Aggregated suspension marker; the physical QuestDB view promotes coalesce to LONG", null));
            } else {
                result.add(column);
            }
        }
        return List.copyOf(result);
    }

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "v_backtest_daily", 1, "derived.questdb.backtest_daily",
            "backtest_daily_owner", "v_backtest_daily", ObjectKind.MATERIALIZED_VIEW,
            columns(), List.of("trade_date", "ts_code"), List.of(), "trade_date",
            Partition.MONTH, true, Set.of(Capability.READ), List.of("backtest_daily"),
            "True native SAMPLE BY1d singleton-per-stock aggregation of the fully enriched unique DAY base. "
                    + "No ordinary intermediate/alias view is installed. is_suspended remains LONG and is_st INT. "
                    + "Reads require verified durable publication, all-four-source pin, exact native SQL/schema, live physical base binding, WAL and refresh catch-up.");
}
