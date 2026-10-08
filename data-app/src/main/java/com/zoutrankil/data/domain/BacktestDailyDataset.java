package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D090 retained-compatibility contract for the audited physical snapshot. */
public final class BacktestDailyDataset {
    private BacktestDailyDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Exchange business date; UTC midnight is only the QuestDB storage carrier");

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "backtest_daily", 1, "python.derived.backtest_daily", "python.backtest_readthrough_compatibility",
            "backtest_daily", ObjectKind.TABLE,
            List.of(
                    new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                            "Exchange business date stored at UTC midnight; not an event instant", TRADE_DATE),
                    new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                            "Exchange-qualified mainland equity code", null),
                    metric("open", "Daily unadjusted price in CNY/share; suspended rows carry the previous close"),
                    metric("high", "Daily unadjusted price in CNY/share; suspended rows carry the previous close"),
                    metric("low", "Daily unadjusted price in CNY/share; suspended rows carry the previous close"),
                    metric("close", "Daily unadjusted close in CNY/share; suspended rows carry the previous close"),
                    metric("vol", "Tushare volume in lots (手); synthesized suspended rows use zero"),
                    metric("amount", "Tushare turnover in thousand CNY (千元); synthesized suspended rows use zero"),
                    metric("adj_factor", "Dimensionless adjustment factor copied from the source price row"),
                    metric("up_limit", "Upper daily price limit in CNY/share, copied from stk_limit when available"),
                    metric("down_limit", "Lower daily price limit in CNY/share, copied from stk_limit when available"),
                    new Column("derived:stk_suspend", "is_suspended", "is_suspended", StorageType.INT, true,
                            "Suspension indicator; coalesced to zero by the current view for normal rows", null),
                    new Column("derived:stk_st_daily", "is_st", "is_st", StorageType.INT, true,
                            "Special-treatment indicator; coalesced to zero by the current view when absent", null)),
            List.of("trade_date", "ts_code"), List.of("trade_date", "ts_code"), "trade_date",
            Partition.DAY, true, Set.of(Capability.READ), List.of(),
            "Preserve the audited legacy DAY/WAL/DEDUP table and its (trade_date, ts_code) key as a read-only compatibility surface. "
                    + "Python owns the source-version read-through cache from v_backtest_daily; the callable legacy materializer is not promoted. "
                    + "No Java write or compute owner is registered, preventing a competing materialization path.");

    private static Column metric(String name, String meaning) {
        return new Column("derived:backtest_daily." + name, name, name, StorageType.DOUBLE, true, meaning, null);
    }
}
