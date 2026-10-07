package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Java owns bounded native materialization and valid typed reads; SQL matches the Python contract. */
public final class MarketBreadthDailyV1Dataset {
    private MarketBreadthDailyV1Dataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "QuestDB SAMPLE BY 1d ALIGN TO CALENDAR of stk_factor.trade_date");

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "mv_market_breadth_daily_v1", 1, "python.derived.market_barometer_views",
            "market_breadth_daily_owner", "mv_market_breadth_daily_v1", ObjectKind.MATERIALIZED_VIEW,
            List.of(
                    new Column("stk_factor.trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP,
                            false, "Exchange calendar date and MV bucket identity", TRADE_DATE),
                    new Column("derived:count()", "stock_count", "stock_count", StorageType.LONG,
                            false, "Rows in source daily bucket", null),
                    new Column("derived:pct_change>0", "up_count", "up_count", StorageType.LONG,
                            false, "Positive pct_change rows", null),
                    new Column("derived:pct_change<0", "down_count", "down_count", StorageType.LONG,
                            false, "Negative pct_change rows", null),
                    new Column("derived:pct_change=0", "flat_count", "flat_count", StorageType.LONG,
                            false, "Zero pct_change rows", null),
                    new Column("derived:avg(pct_change)", "avg_pct_change", "avg_pct_change", StorageType.DOUBLE,
                            true, "Arithmetic mean of source percentage change", null),
                    new Column("derived:sum(amount)/100000", "total_amount_yi", "total_amount_yi",
                            StorageType.DOUBLE, true, "Source amount converted to yi according to Python SQL", null)),
            List.of("trade_date"), List.of(), "trade_date", Partition.MONTH, true,
            Set.of(Capability.READ), List.of("stk_factor"),
            "One SAMPLE BY calendar day is the natural key; native MV has MONTH/WAL and no UPSERT key. "
                    + "Java runs native REFRESH through the canonical job and shared ledger. Direct row writes are forbidden; read rejects invalid "
                    + "or lagging refresh state and binds pagination to source identity, refresh checkpoint and MV physical revision.");
}
