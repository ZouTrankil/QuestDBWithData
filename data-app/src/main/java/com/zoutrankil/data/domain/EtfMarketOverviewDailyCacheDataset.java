package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D101 active read-through contract; canonical management delegates publication to the original Python owner. */
public final class EtfMarketOverviewDailyCacheDataset {
    private EtfMarketOverviewDailyCacheDataset() {}
    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Original Python daily SAMPLE BY date; exact UTC-midnight TIMESTAMP calendar carrier");

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "etf_market_overview_daily_cache", 1, "python.derived.market_barometer_cache",
            "python.MarketBarometerReadThroughCache.read", "etf_market_overview_daily_cache", ObjectKind.TABLE,
            List.of(
                    new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                            "Daily joined ETF overview bucket and complete generation key", TRADE_DATE),
                    new Column("etf_count", "etf_count", "etf_count", StorageType.LONG, false,
                            "Required exact count_distinct of full etf_share.ts_code after the date/code INNER JOIN", null),
                    new Column("total_share", "total_share", "total_share", StorageType.DOUBLE, true,
                            "Sum of joined etf_share.fd_share in ten-thousand-share units; no additional conversion", null),
                    new Column("total_size_yi", "total_size_yi", "total_size_yi", StorageType.DOUBLE, true,
                            "Yi yuan: original sum(fd_share * etf_daily.close)/10000.0; preserve nulls and stored values", null),
                    new Column("source_version", "source_version", "source_version", StorageType.SYMBOL, false,
                            "Complete lowercase SHA-256 of original SQL and etf_share/etf_daily/relevant timeless etf_basic partition state", null)),
            List.of("trade_date", "source_version"), List.of("trade_date", "source_version"),
            "trade_date", Partition.MONTH, true, Set.of(Capability.READ, Capability.WRITE),
            List.of("etf_share", "etf_daily", "etf_basic"),
            "Active Python read-through owner remains the unique cache and coverage publisher. Canonical Java bounded "
                    + "prewarm/materialize management delegates to the original read(), including hits, misses and _publish; "
                    + "it does not create a competing Java receipt writer. Preserve MONTH/WAL/DEDUP date/source-generation key. "
                    + "etf_basic participates in source-version hashing as a timeless dependency, not the aggregate JOIN or a fund filter. "
                    + "Typed cache rows alone do not certify a current hit, coverage receipt or provider/universe completeness. "
                    + "Pagination binds actual cache table identity and physical/WAL version independently of business SHA. "
                    + "Typed WRITE accepts assertions of the current original owner's complete five-field result only; "
                    + "fresh source previews reject arbitrary DTO values and historical generations before any publication. "
                    + "The delegated write-group calls the same Python read() after durable submission; standalone receipt "
                    + "writes, static replacement and WAL replacement are not admitted.");
}
