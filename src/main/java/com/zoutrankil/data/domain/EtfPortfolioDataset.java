package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D018 contract for the externally owned etf_portfolio table; formal DDL remains audit-only. */
public final class EtfPortfolioDataset {
    public static final int MAX_ROWS_PER_ANN_DATE = 1_000_000;
    public static final String ISOLATED_PREFIX = "java_d018_etf_portfolio_";
    private static final TemporalContract ANN_DATE = new TemporalContract(TemporalKind.BUSINESS_DATE,
            "BASIC", "calendar", "DAY", "Tushare announcement date; UTC-midnight storage carrier, not an instant");
    private static final TemporalContract END_DATE = new TemporalContract(TemporalKind.BUSINESS_DATE,
            "BASIC", "calendar", "DAY", "Tushare portfolio report-period date; designated timestamp carrier");
    private static final TemporalContract UPDATE_TIME = new TemporalContract(TemporalKind.INSTANT,
            "ISO_INSTANT", "UTC", "MICROS", "Frozen UTC Java observation time; Python preprocessing adds this non-provider field");

    private EtfPortfolioDataset() {}
    public static final DatasetDefinition DEFINITION = definition("etf_portfolio");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("etf_portfolio", 1, "tushare.fund_portfolio", "etf_portfolio_owner", table,
                ObjectKind.TABLE, List.of(
                new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                        "Fund identifier including exchange suffix", null),
                new Column("ann_date", "ann_date", "ann_date", StorageType.TIMESTAMP, false,
                        "Provider announcement calendar date; source sync cursor", ANN_DATE),
                new Column("end_date", "end_date", "end_date", StorageType.TIMESTAMP, false,
                        "Provider report-period date and physical designated timestamp", END_DATE),
                new Column("symbol", "symbol", "symbol", StorageType.SYMBOL, false,
                        "Underlying holding security identifier including exchange suffix", null),
                new Column("mkv", "mkv", "mkv", StorageType.DOUBLE, true,
                        "Holding market value in yuan; raw nullable provider DOUBLE", null),
                new Column("amount", "amount", "amount", StorageType.DOUBLE, true,
                        "Holding amount in shares; raw nullable provider DOUBLE", null),
                new Column("stk_mkv_ratio", "stk_mkv_ratio", "stk_mkv_ratio", StorageType.DOUBLE, true,
                        "Share of the security market value, provider percentage units retained", null),
                new Column("stk_float_ratio", "stk_float_ratio", "stk_float_ratio", StorageType.DOUBLE, true,
                        "Share of the security float, provider percentage units retained", null),
                new Column("derived:frozen-observation-time", "update_time", "update_time", StorageType.TIMESTAMP, false,
                        "Frozen Java observation time at microsecond precision; not source revision time", UPDATE_TIME)),
                List.of("ts_code", "ann_date", "end_date", "symbol"),
                List.of("ts_code", "ann_date", "end_date", "symbol"), "end_date", Partition.YEAR, true,
                Set.of(Capability.READ, Capability.WRITE), List.of(),
                "Retains audited YEAR/WAL/DEDUP and all four key dimensions. ann_date is the incremental source date; end_date is the designated report-period timestamp. No Flyway migration changes the externally owned formal table; isolated acceptance DDL only." );
    }

    public static String createIsolatedTableSql(String table) {
        requireIsolatedTable(table);
        var columns = DEFINITION.columns().stream()
                .map(column -> "\"" + column.storageName() + "\" " + column.storageType().name()).toList();
        return "CREATE TABLE " + table + " (" + String.join(", ", columns)
                + ") TIMESTAMP(end_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code,ann_date,end_date,symbol)";
    }

    public static void requireIsolatedTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalStateException("D018 execution requires java_d018_etf_portfolio_<suffix>");
    }

    /** Admits the existing formal table or the original isolated execution namespace. */
    public static void requireExecutionTable(String table) {
        if (!"etf_portfolio".equals(table)) requireIsolatedTable(table);
    }
}
