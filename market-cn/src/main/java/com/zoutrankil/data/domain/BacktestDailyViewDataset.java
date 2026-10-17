package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** The native automatically refreshed backtest MV, built directly from the four raw sources. */
public final class BacktestDailyViewDataset {
    private BacktestDailyViewDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Exchange business date; UTC midnight is only the QuestDB storage carrier");

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "mv_backtest_daily", 1, "derived.questdb.backtest_daily", "questdb_native_mv",
            "mv_backtest_daily", ObjectKind.MATERIALIZED_VIEW,
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
                    new Column("derived:stk_suspend", "is_suspended", "is_suspended", StorageType.LONG, true,
                            "Suspension indicator; coalesced to zero by the current view for normal rows", null),
                    new Column("derived:stk_st_daily", "is_st", "is_st", StorageType.INT, true,
                            "Special-treatment indicator; coalesced to zero by the current view when absent", null)),
            List.of("trade_date", "ts_code"), List.of(), "trade_date",
            Partition.MONTH, true, Set.of(Capability.READ), List.of("stk_factor","stk_limit","stk_suspend","stk_st_daily"),
            "QuestDB owns the 13-column materialized result, including prior-price suspension rows. "
                    + "REFRESH IMMEDIATE listens to stk_limit; ingest stk_factor, stk_suspend and stk_st_daily first, then stk_limit. "
                    + "Applications only ingest raw tables and read mv_backtest_daily; no backtest result table, staging, publication or refresh job.");

    private static Column metric(String name, String meaning) {
        return new Column("derived:mv_backtest_daily." + name, name, name, StorageType.DOUBLE, true, meaning, null);
    }
}
