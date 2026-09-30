package com.zoutrankil.questdbwithdata.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** D091 read-only contract for Python's post-publication cache integrity receipts. */
public final class BacktestDailyCacheCoverageDataset {
    private BacktestDailyCacheCoverageDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Exchange business date for the cached slice; UTC midnight is only the storage carrier");

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "backtest_daily_cache_coverage", 1, "python.derived.backtest_daily_cache", 
            "python.BacktestReadThroughCache._publish", "backtest_daily_cache_coverage", ObjectKind.TABLE,
            List.of(
                    new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                            "Exchange business date of the cached daily slice; UTC-midnight storage carrier", TRADE_DATE),
                    new Column("source_version", "source_version", "source_version", StorageType.SYMBOL, false,
                            "SHA-256 fingerprint of the view definition and source partition transaction state", null),
                    new Column("row_count", "row_count", "row_count", StorageType.LONG, false,
                            "Number of matching backtest_daily_cache rows for this complete key", null),
                    new Column("content_digest", "content_digest", "content_digest", StorageType.STRING, false,
                            "SHA-256 of the canonical sorted pandas hash_pandas_object values for the cached 13-field slice", null)),
            List.of("trade_date", "source_version"), List.of("trade_date", "source_version"), "trade_date",
            Partition.MONTH, true, Set.of(Capability.READ), List.of(),
            "Preserve the Python-owned MONTH/WAL/DEDUP integrity-receipt table and full (trade_date, source_version) key. "
                    + "Python publishes coverage only after verifying cache row visibility, count, unique keys and content digest. "
                    + "This receipt is not a source-ingest checkpoint; Java is read-only to avoid forging or racing the cache owner's assertion.");
}
