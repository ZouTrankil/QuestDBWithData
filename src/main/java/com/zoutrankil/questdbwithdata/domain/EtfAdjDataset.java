package com.zoutrankil.questdbwithdata.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** D015 definition for the externally owned, audited etf_adj table. */
public final class EtfAdjDataset {
    private EtfAdjDataset() {}
    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "Tushare fund adjustment trade date");

    public static final DatasetDefinition DEFINITION = definition("etf_adj");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("etf_adj", 1, "tushare.fund_adj", "etf_adj_owner", table,
                ObjectKind.TABLE, columns(), List.of("ts_code", "trade_date"), List.of("ts_code", "timestamp"),
                "timestamp", Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE),
                List.of("exchange_calendar"),
                "Retain YEAR/WAL/dedup (ts_code,timestamp) with TIMESTAMP microsecond storage. Source trade_date maps explicitly to a UTC-midnight business-date carrier; no precision loss and no formal-table DDL.");
    }

    public static List<Column> columns() {
        return List.of(
                new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                        "Tushare fund identity with exchange suffix", null),
                new Column("trade_date", "trade_date", "timestamp", StorageType.TIMESTAMP, false,
                        "Business trading date stored as UTC-midnight carrier, not an instant", TRADE_DATE),
                new Column("adj_factor", "adj_factor", "adj_factor", StorageType.DOUBLE, true,
                        "Unscaled Tushare fund adjustment factor", null));
    }

    /** DDL for a task-owned acceptance table only; never applied to the externally owned formal table. */
    public static String createIsolatedTableSql(String table) {
        DatasetDefinition.identifier(table);
        String prefix = "java_d015_etf_adj_";
        if (!table.startsWith(prefix) || table.length() <= prefix.length())
            throw new IllegalArgumentException("D015 isolated table name required");
        var definitions = new ArrayList<String>();
        for (var column : columns()) definitions.add("\"" + column.storageName() + "\" " + column.storageType().name());
        return "CREATE TABLE " + table + " (" + String.join(", ", definitions)
                + ") TIMESTAMP(timestamp) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, timestamp)";
    }
}
