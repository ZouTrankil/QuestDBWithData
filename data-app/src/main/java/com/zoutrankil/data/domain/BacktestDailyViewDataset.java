package com.zoutrankil.data.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Read-only compatibility name over the sole enriched backtest result table. */
public final class BacktestDailyViewDataset {
    private BacktestDailyViewDataset() {}

    private static List<Column> columns() {
        var result = new ArrayList<Column>();
        for (var column : BacktestDailyDataset.DEFINITION.columns()) {
            if (column.storageName().equals("is_suspended")) {
                result.add(new Column("derived:stk_suspend", "is_suspended", "is_suspended", StorageType.LONG,
                        true, "Suspension marker cast to LONG to preserve the public read contract", null));
            } else {
                result.add(column);
            }
        }
        return List.copyOf(result);
    }

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "v_backtest_daily", 1, "derived.questdb.backtest_daily",
            "backtest_daily_owner", "v_backtest_daily", ObjectKind.VIEW,
            columns(), List.of("trade_date", "ts_code"), List.of(), "trade_date",
            Partition.NONE, false, Set.of(Capability.READ), List.of("backtest_daily"),
            "Direct 13-column read view of the unique enriched backtest_daily table. "
                    + "The view stores no business rows and needs no refresh. is_suspended remains LONG and is_st INT. "
                    + "Reads require the verified base publication, all-four-source pin, canonical view SQL/schema and settled base WAL.");
}
