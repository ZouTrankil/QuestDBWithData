package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D102 direct ordinary view contract, independent of the versioned D101 cache. */
public final class EtfMarketOverviewDailyViewDataset {
    private EtfMarketOverviewDailyViewDataset() {}

    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "SAMPLE BY 1d ALIGN TO CALENDAR aggregate date carried at exact UTC midnight; not an event timestamp");

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "v_etf_market_overview_daily", 1, "python.derived.market_barometer_views",
            "python.market_barometer_views.install_views", "v_etf_market_overview_daily", ObjectKind.VIEW,
            List.of(
                    new Column("etf_share.timestamp", "trade_date", "trade_date",
                            StorageType.TIMESTAMP, false, "Calendar-day identity of the direct join aggregate", TRADE_DATE),
                    new Column("derived:count_distinct(etf_share.ts_code)", "etf_count", "etf_count",
                            StorageType.LONG, false, "Exact nonnegative distinct full-code count after same-code/same-timestamp INNER JOIN", null),
                    new Column("derived:sum(etf_share.fd_share)", "total_share", "total_share",
                            StorageType.DOUBLE, true, "Joined ETF shares in ten-thousand-share units (万份); preserve SQL null and signed values", null),
                    new Column("derived:sum(etf_share.fd_share*etf_daily.close)/10000.0", "total_size_yi", "total_size_yi",
                            StorageType.DOUBLE, true, "Joined ETF market value in yi yuan (亿元); division belongs to source SQL, no mapper scaling", null)),
            List.of("trade_date"), List.of(), "trade_date", Partition.NONE, false,
            Set.of(Capability.READ), List.of("etf_share", "etf_daily"),
            "Ordinary direct share/daily view, SAMPLE BY 1d ALIGN TO CALENDAR after an INNER JOIN on complete timestamp and ts_code. "
                    + "No basic JOIN, code/type filter, cache/receipt dependency, physical partition, WAL or UPSERT key. "
                    + "Direct writes and replacements are forbidden. Existing base owners refresh share/daily; "
                    + "D101 prewarms its separate cache and does not materialize this view. "
                    + "Reads bind the exact view SQL and both base tables' stable physical/WAL versions; date alone is the natural identity. "
                    + "Typed reads require the four columns and a date equality or a bounded date range of at most 31 days, with pages of at most 31 rows. "
                    + "Nullable finite DOUBLE aggregates retain exact binary64 values, signed zero, units and SQL nulls.");
}
