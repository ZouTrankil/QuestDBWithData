package com.zoutrankil.data.domain;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D010 mapping for the externally owned, audited stk_limit table. */
public final class StockLimitDataset {
    private StockLimitDataset() {}
    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "Tushare exchange trade date");

    public static final DatasetDefinition DEFINITION = definition("stk_limit");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("stk_limit", 1, "tushare.stk_limit", "stk_limit_owner", table,
                ObjectKind.TABLE, columns(), List.of("ts_code", "trade_date"), List.of("ts_code", "trade_date"),
                "trade_date", Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE),
                List.of("exchange_calendar"),
                "Retain the audited external YEAR/WAL/dedup target. Python declares DAY, but no formal-table DDL is applied; full (ts_code, trade_date) identity is preserved.");
    }

    public static List<Column> columns() {
        return List.of(
                new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                        "Tushare instrument identity with exchange suffix", null),
                new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                        "Business trading date stored as UTC-midnight carrier, not an instant", TRADE_DATE),
                new Column("up_limit", "up_limit", "up_limit", StorageType.DOUBLE, true,
                        "Upper daily price limit, source currency per share; nullable as reported", null),
                new Column("down_limit", "down_limit", "down_limit", StorageType.DOUBLE, true,
                        "Lower daily price limit, source currency per share; nullable as reported", null));
    }

    /** DDL for a task-owned acceptance table only; never applied to the externally owned formal table. */
    public static String createIsolatedTableSql(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith("java_d010_stk_limit_") || table.length() <= "java_d010_stk_limit_".length())
            throw new IllegalArgumentException("D010 isolated table name required");
        var definitions = new ArrayList<String>();
        for (var column : columns()) definitions.add(column.storageName() + " " + column.storageType().name());
        return "CREATE TABLE " + table + " (" + String.join(", ", definitions)
                + ") TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, trade_date)";
    }
}
