package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Enriched composite-key base for the public native backtest materialized view. */
public final class BacktestDailyDataset {
    private BacktestDailyDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Exchange business date; UTC midnight is only the QuestDB storage carrier");

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "backtest_daily", 1, "derived.questdb.backtest_daily", "backtest_daily_owner",
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
            Partition.DAY, true, Set.of(Capability.READ,Capability.WAL_REPLACE), List.of("stk_factor","stk_limit","stk_suspend","stk_st_daily"),
            "Java owns the canonical 13-field enrichment including historical ASOF suspension rows. "
                    + "First publication bootstraps the complete source history; later explicit DAY windows preserve physical base identity and retain old-window backups. "
                    + "The native v_backtest_daily refresh and its source-pinned publication receipt must complete before guarded reads.");

    private static Column metric(String name, String meaning) {
        return new Column("derived:backtest_daily." + name, name, name, StorageType.DOUBLE, true, meaning, null);
    }
}
