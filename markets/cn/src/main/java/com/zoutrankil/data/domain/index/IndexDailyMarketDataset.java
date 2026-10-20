package com.zoutrankil.data.domain.index;

import com.zoutrankil.data.domain.DatasetDefinition;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** 正式指数日线契约与旧隔离验收布局分开，避免实库和恢复版本混用。 */
public final class IndexDailyMarketDataset {
    private IndexDailyMarketDataset() {}
    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "Index trading date; UTC-midnight TIMESTAMP carrier");
    private static final TemporalContract UPDATE_TIME = new TemporalContract(
            TemporalKind.INSTANT, "ISO_INSTANT", "UTC", "MICROS", "Frozen Java source-observation time (technical metadata)");
    public static final DatasetDefinition DEFINITION = definition("index_daily_market");
    public static final DatasetDefinition LEGACY_ISOLATED_DEFINITION = legacyIsolatedDefinition(
            "java_d019_index_daily_market_acceptance");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return layout(table, 2, "trade_date");
    }

    /** 旧隔离作业的 v1 布局不改，冻结请求和恢复凭证继续绑定原物理列。 */
    public static DatasetDefinition legacyIsolatedDefinition(String table) {
        com.zoutrankil.data.domain.shared.policy.IsolatedTablePolicy.INDEX_DAILY_MARKET.require(table);
        return layout(table, 1, "timestamp");
    }

    private static DatasetDefinition layout(String table, int version, String dateColumn) {
        return new DatasetDefinition("index_daily_market", version, "tushare.index_daily+sw_daily",
                "index_daily_market_owner", table, ObjectKind.TABLE, columns(dateColumn),
                List.of("ts_code", "trade_date"), List.of("ts_code", dateColumn), dateColumn,
                Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE),
                List.of("index", "index_member"),
                "保留 YEAR/WAL/DEDUP；v2 正式表使用 trade_date，v1 隔离表使用 timestamp。 "
                        + "trade_date is a calendar date carried at UTC midnight; update_time is a frozen technical observation. "
                        + "No formal-table DDL is applied because the table is externally owned.");
    }
    public static List<Column> columns() { return columns("trade_date"); }
    private static List<Column> columns(String dateColumn) {
        return List.of(
                new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false, "Frozen canonical Tushare index identity", null),
                new Column("trade_date", "trade_date", dateColumn, StorageType.TIMESTAMP, false, "Index trading business date; UTC-midnight carrier", TRADE_DATE),
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
