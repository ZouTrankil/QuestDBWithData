package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Stable ETF public view; supports staged direct SQL and verified native MV activation. */
public final class EtfMarketOverviewDailyViewDataset {
    private EtfMarketOverviewDailyViewDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "SAMPLE BY 1d ALIGN TO CALENDAR aggregate date carried at exact UTC midnight; not an event timestamp");

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "mv_etf_market_overview_daily_v1", 1, "python.derived.market_barometer_views",
            "java.etf_overview_maintenance", "mv_etf_market_overview_daily_v1", ObjectKind.MATERIALIZED_VIEW,
            List.of(
                    new Column("etf_share.timestamp", "trade_date", "trade_date",
                            StorageType.TIMESTAMP, false, "Calendar-day identity of the direct join aggregate", TRADE_DATE),
                    new Column("derived:count_distinct(etf_share.ts_code)", "etf_count", "etf_count",
                            StorageType.LONG, false, "Exact nonnegative distinct full-code count after same-code/same-timestamp INNER JOIN", null),
                    new Column("derived:sum(etf_share.fd_share)", "total_share", "total_share",
                            StorageType.DOUBLE, true, "Joined ETF shares in ten-thousand-share units (万份); preserve SQL null and signed values", null),
                    new Column("derived:sum(etf_share.fd_share*etf_daily.close)/10000.0", "total_size_yi", "total_size_yi",
                            StorageType.DOUBLE, true, "Joined ETF market value in yi yuan (亿元); division belongs to source SQL, no mapper scaling", null)),
            List.of("trade_date"), List.of(), "trade_date", Partition.MONTH, true,
            Set.of(Capability.READ), List.of("etf_share", "etf_daily"),
            "Native share/daily MV with one calendar-day bucket after a timestamp/code INNER JOIN. "
                    + "QuestDB maintains share increments; Java verifies both source revisions in its local SQLite ledger. "
                    + "Daily-price revisions require a verified full refresh. Reads require all four fields and at most 31 days.");
}
