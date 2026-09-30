package com.zoutrankil.data.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D093 read-only contract for the authoritative Python-installed ordinary view. */
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
            "v_backtest_daily", 1, "python.derived.backtest_view",
            "python.backtest_view.install_view", "v_backtest_daily", ObjectKind.VIEW,
            columns(), List.of("trade_date", "ts_code"), List.of(), null,
            Partition.NONE, false, Set.of(Capability.READ), List.of(),
            "Ordinary view of stk_factor, stk_limit, grouped stk_suspend and stk_st_daily. "
                    + "Python owns VIEW_SELECT and installation; Java reads the existing definition only. "
                    + "No physical partition, WAL, DEDUP or direct writes belong to the view. "
                    + "The source models require unique date/stock keys, and suspension rows are grouped before joining.");
}
