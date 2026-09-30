package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D094 read-only contract for Python's shared barometer cache coverage table. */
public final class MarketBarometerCacheCoverageDataset {
    private MarketBarometerCacheCoverageDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Exchange business date of one barometer daily cache entry");

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "market_barometer_cache_coverage", 1, "python.derived.market_barometer_cache",
            "python.MarketBarometerReadThroughCache._publish", "market_barometer_cache_coverage", ObjectKind.TABLE,
            List.of(
                    new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                            "Exchange business date carried by UTC-midnight storage timestamp", TRADE_DATE),
                    new Column("dataset_id", "dataset_id", "dataset_id", StorageType.SYMBOL, false,
                            "Market breadth, ETF overview or retail sentiment product identifier", null),
                    new Column("source_version", "source_version", "source_version", StorageType.SYMBOL, false,
                            "SHA-256 fingerprint of product SQL and relevant source table state", null),
                    new Column("row_count", "row_count", "row_count", StorageType.LONG, false,
                            "Matching versioned daily cache row count, zero or one", null),
                    new Column("content_digest", "content_digest", "content_digest", StorageType.STRING, false,
                            "SHA-256 of the canonical cached record, including the empty-day digest", null)),
            List.of("trade_date", "dataset_id", "source_version"),
            List.of("trade_date", "dataset_id", "source_version"), "trade_date",
            Partition.MONTH, true, Set.of(Capability.READ), List.of(),
            "Python's read-through owner publishes this shared receipt after verifying cache visibility and content. "
                    + "The three products have separate physical cache tables and may publish zero-row days. "
                    + "Java reads receipts only, preserving MONTH/WAL/DEDUP and the complete versioned key.");
}
