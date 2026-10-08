package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D097 compatibility contract; Python remains the sole cache and coverage publisher. */
public final class MarketBreadthDailyCacheDataset {
    private MarketBreadthDailyCacheDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Python daily aggregation date; TIMESTAMP stores its exact UTC-midnight carrier");

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "market_breadth_daily_cache", 1, "python.derived.market_barometer_cache",
            "python.MarketBarometerReadThroughCache._publish", "market_breadth_daily_cache", ObjectKind.TABLE,
            List.of(
                    new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP,
                            false, "Daily SAMPLE BY calendar bucket and versioned cache key", TRADE_DATE),
                    new Column("stock_count", "stock_count", "stock_count", StorageType.LONG,
                            false, "Source daily row count, including null pct_change", null),
                    new Column("up_count", "up_count", "up_count", StorageType.LONG,
                            false, "Rows with positive source pct_change", null),
                    new Column("down_count", "down_count", "down_count", StorageType.LONG,
                            false, "Rows with negative source pct_change", null),
                    new Column("flat_count", "flat_count", "flat_count", StorageType.LONG,
                            false, "Rows with zero source pct_change; null is not flat", null),
                    new Column("avg_pct_change", "avg_pct_change", "avg_pct_change", StorageType.DOUBLE,
                            true, "Mean source percentage change in percentage points; pass through or null", null),
                    new Column("total_amount_yi", "total_amount_yi", "total_amount_yi", StorageType.DOUBLE,
                            true, "Yi yuan: Python sum(amount)/100000; pass through without another conversion", null),
                    new Column("source_version", "source_version", "source_version", StorageType.SYMBOL,
                            false, "Lowercase SHA-256 of product SQL and relevant source table identity/partition state", null)),
            List.of("trade_date", "source_version"), List.of("trade_date", "source_version"),
            "trade_date", Partition.MONTH, true, Set.of(Capability.READ), List.of(),
            "Retain Python's legacy MONTH/WAL/DEDUP cache with the complete date/source-generation key. "
                    + "The active native alias bypasses the read-through cache. Python remains its only publisher, "
                    + "verifies WAL-visible row content before publishing market_barometer_cache_coverage, "
                    + "and does not write a cache row for an empty day. Java reads historical generations only; "
                    + "a cache row alone does not certify current source freshness or a published coverage receipt. "
                    + "Bounded pagination pins the actual cache table identity and physical/WAL version.");
}
