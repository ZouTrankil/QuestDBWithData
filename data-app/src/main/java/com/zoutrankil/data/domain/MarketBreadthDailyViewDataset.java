package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D096 contract for the existing public alias of the D095 native materialized view. */
public final class MarketBreadthDailyViewDataset {
    private MarketBreadthDailyViewDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Pass-through native SAMPLE BY 1d calendar bucket; TIMESTAMP carries the date at UTC midnight exactly");

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "v_market_breadth_daily", 1, "python.derived.market_barometer_views",
            "market_breadth_daily_owner", "v_market_breadth_daily", ObjectKind.VIEW,
            List.of(
                    new Column("mv_market_breadth_daily_v1.trade_date", "trade_date", "trade_date",
                            StorageType.TIMESTAMP, false, "Calendar-day identity; no event-time conversion", TRADE_DATE),
                    new Column("mv_market_breadth_daily_v1.stock_count", "stock_count", "stock_count",
                            StorageType.LONG, false, "Rows in the source daily bucket, including null pct_change", null),
                    new Column("mv_market_breadth_daily_v1.up_count", "up_count", "up_count",
                            StorageType.LONG, false, "Rows with positive source pct_change", null),
                    new Column("mv_market_breadth_daily_v1.down_count", "down_count", "down_count",
                            StorageType.LONG, false, "Rows with negative source pct_change", null),
                    new Column("mv_market_breadth_daily_v1.flat_count", "flat_count", "flat_count",
                            StorageType.LONG, false, "Rows with zero source pct_change; null is not flat", null),
                    new Column("mv_market_breadth_daily_v1.avg_pct_change", "avg_pct_change", "avg_pct_change",
                            StorageType.DOUBLE, true, "Mean source percentage change in percentage points; null stays null", null),
                    new Column("mv_market_breadth_daily_v1.total_amount_yi", "total_amount_yi", "total_amount_yi",
                            StorageType.DOUBLE, true, "Yi yuan aggregate already converted by D095 sum(amount)/100000; no second conversion", null)),
            List.of("trade_date"), List.of(), "trade_date", Partition.NONE, false,
            Set.of(Capability.READ), List.of("mv_market_breadth_daily_v1"),
            "Ordinary SELECT * alias of mv_market_breadth_daily_v1 with one calendar bucket per date. "
                    + "The view has no physical partition, WAL or UPSERT key and rejects direct writes. "
                    + "D095 is the canonical native refresh job; D096 adds no competing writer. "
                    + "Reads verify the exact alias binding and the base MV validity, catch-up and stable source/output version.");
}
