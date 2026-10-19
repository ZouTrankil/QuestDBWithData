package com.zoutrankil.data.domain;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D014 mapping for the externally owned, audited etf_daily table. */
public final class EtfDailyDataset {
    private EtfDailyDataset() {}
    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "Tushare exchange trade date");

    public static final DatasetDefinition DEFINITION = definition("etf_daily");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("etf_daily", 1, "tushare.fund_daily", "etf_daily_owner", table,
                ObjectKind.TABLE, columns(), List.of("ts_code", "trade_date"), List.of("ts_code", "timestamp"),
                "timestamp", Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE),
                List.of("exchange_calendar"),
                "Retain YEAR/WAL/dedup (ts_code,timestamp) with TIMESTAMP_NS storage. Source trade_date maps explicitly to business date and the UTC-midnight nanosecond carrier; no precision loss or formal-table DDL.");
    }

    public static List<Column> columns() {
        return List.of(
                new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                        "Tushare instrument identity with exchange suffix", null),
                new Column("trade_date", "trade_date", "timestamp", StorageType.TIMESTAMP_NS, false,
                        "Business trading date stored as UTC-midnight carrier, not an instant", TRADE_DATE),
                new Column("pre_close", "pre_close", "pre_close", StorageType.DOUBLE, true, "Previous close in yuan", null),
                new Column("open", "open", "open", StorageType.DOUBLE, true, "Open in yuan", null),
                new Column("high", "high", "high", StorageType.DOUBLE, true, "High in yuan", null),
                new Column("low", "low", "low", StorageType.DOUBLE, true, "Low in yuan", null),
                new Column("close", "close", "close", StorageType.DOUBLE, true, "Close in yuan", null),
                new Column("change", "change", "change", StorageType.DOUBLE, true, "Change in yuan", null),
                new Column("pct_chg", "pct_chg", "pct_chg", StorageType.DOUBLE, true, "Percent points, not a fraction", null),
                new Column("vol", "vol", "vol", StorageType.DOUBLE, true, "Volume in lots", null),
                new Column("amount", "amount", "amount", StorageType.DOUBLE, true, "Turnover in thousand yuan", null));
    }

    /** DDL for a task-owned acceptance table only; never applied to the externally owned formal table. */
    public static String createIsolatedTableSql(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith("java_d014_etf_daily_") || table.length() <= "java_d014_etf_daily_".length())
            throw new IllegalArgumentException("D014 isolated table name required");
        var definitions = new ArrayList<String>();
        for (var column : columns()) definitions.add("\"" + column.storageName() + "\" " + column.storageType().name());
        return "CREATE TABLE " + table + " (" + String.join(", ", definitions)
                + ") TIMESTAMP(timestamp) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, timestamp)";
    }

    /** Admits the existing formal table or the original isolated execution namespace. */
    public static void requireExecutionTable(String table) {
        if (!"etf_daily".equals(table)) com.zoutrankil.data.domain.policy.IsolatedTablePolicy.ETF_DAILY.require(table);
    }
}
