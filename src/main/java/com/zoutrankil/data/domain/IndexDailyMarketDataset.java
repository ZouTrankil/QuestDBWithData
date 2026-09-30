package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D019's explicit contract for the externally owned index_daily_market table. */
public final class IndexDailyMarketDataset {
    private IndexDailyMarketDataset() {}
    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "Index trading date; UTC-midnight TIMESTAMP carrier");
    private static final TemporalContract UPDATE_TIME = new TemporalContract(
            TemporalKind.INSTANT, "ISO_INSTANT", "UTC", "MICROS", "Frozen Java source-observation time (technical metadata)");
    public static final DatasetDefinition DEFINITION = definition("index_daily_market");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("index_daily_market", 1, "tushare.index_daily+sw_daily",
                "index_daily_market_owner", table, ObjectKind.TABLE, columns(),
                List.of("ts_code", "trade_date"), List.of("ts_code", "timestamp"), "timestamp",
                Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE),
                List.of("index", "index_member"),
                "Preserve the audited YEAR/WAL/DEDUP layout and (ts_code,timestamp) physical key. "
                        + "trade_date is a calendar date carried at UTC midnight; update_time is a frozen technical observation. "
                        + "No formal-table DDL is applied because the table is externally owned.");
    }
    public static List<Column> columns() {
        return List.of(
                new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false, "Frozen canonical Tushare index identity", null),
                new Column("trade_date", "trade_date", "timestamp", StorageType.TIMESTAMP, false, "Index trading business date; UTC-midnight carrier", TRADE_DATE),
                number("close", "Closing index points"), number("open", "Opening index points"),
                number("high", "Highest index points"), number("low", "Lowest index points"),
                number("pre_close", "Previous close index points"), number("change", "Point change"),
                number("pct_chg", "Percentage-point change, not a fraction"),
                number("vol", "Endpoint-native index volume; no conversion"),
                number("amount", "Endpoint-native index turnover; no conversion"),
                new Column("derived:frozen_observed_at", "update_time", "update_time", StorageType.TIMESTAMP,
                        false, "Frozen technical observation timestamp at microsecond precision", UPDATE_TIME));
    }
    private static Column number(String name, String meaning) {
        return new Column(name, name, name, StorageType.DOUBLE, true, meaning, null);
    }
    /** DDL helper is restricted to an explicit isolated acceptance table name. */
    public static String createIsolatedTableSql(String table) {
        DatasetDefinition.identifier(table);
        String prefix = "java_d019_index_daily_market_";
        if (!table.startsWith(prefix) || table.length() <= prefix.length())
            throw new IllegalArgumentException("D019 isolated table name required");
        return "CREATE TABLE \"" + table + "\" (ts_code SYMBOL, timestamp TIMESTAMP, close DOUBLE, open DOUBLE, high DOUBLE, low DOUBLE, "
                + "pre_close DOUBLE, change DOUBLE, pct_chg DOUBLE, vol DOUBLE, amount DOUBLE, update_time TIMESTAMP) "
                + "TIMESTAMP(timestamp) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, timestamp)";
    }
}
