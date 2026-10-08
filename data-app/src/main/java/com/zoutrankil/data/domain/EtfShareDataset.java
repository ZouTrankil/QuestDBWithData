package com.zoutrankil.data.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D016 mapping for the externally owned etf_share table; isolated D016 objects only. */
public final class EtfShareDataset {
    public static final int MAX_ROWS_PER_DATE = 5997;
    public static final String ISOLATED_PREFIX = "java_d016_etf_share_";
    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
            "Tushare trade_date, stored in timestamp as a UTC-midnight calendar carrier");
    private static final TemporalContract UPDATE_TIME = new TemporalContract(
            TemporalKind.INSTANT, "ISO_INSTANT", "UTC", "MICROS",
            "Frozen Java source observation time; Python model generates update_time at write preparation, not a Tushare field");

    private EtfShareDataset() {}
    public static final DatasetDefinition DEFINITION = definition("etf_share");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("etf_share", 1, "tushare.fund_share", "etf_share_owner", table,
                ObjectKind.TABLE, List.of(
                new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                        "Complete Tushare fund identity including SH/SZ exchange or OF OTC suffix", null),
                new Column("trade_date", "trade_date", "timestamp", StorageType.TIMESTAMP, false,
                        "Provider calendar date YYYYMMDD, stored as UTC-midnight carrier rather than event instant", TRADE_DATE),
                new Column("fd_share", "fd_share", "fd_share", StorageType.DOUBLE, true,
                        "Tushare fund shares in ten-thousand-share units; raw nullable DOUBLE", null),
                new Column("fund_type", "fund_type", "fund_type", StorageType.STRING, true,
                        "Nullable provider fund_share field; preserve explicit nulls as source revisions", null),
                new Column("market", "market", "market", StorageType.SYMBOL, false,
                        "Provider fund_share market field cross-checked against request partition; OF suffix maps to O", null),
                new Column("derived:frozen-observation-time", "update_time", "update_time", StorageType.TIMESTAMP, false,
                        "Frozen UTC observation timestamp at microsecond precision; technical metadata, not source revision time", UPDATE_TIME)),
                List.of("ts_code", "trade_date"), List.of("ts_code", "timestamp"), "timestamp", Partition.YEAR, true,
                Set.of(Capability.READ, Capability.WRITE), List.of("exchange_calendar"),
                "Retain audited YEAR/WAL/DEDUP key (ts_code,timestamp). timestamp carries trade_date at UTC midnight and TIMESTAMP microsecond precision. No formal-table Flyway DDL; acceptance DDL is D016-isolated only. fund_type and market are explicit provider fields confirmed by the bounded D016 source probe; update_time remains frozen technical observation metadata.");
    }

    public static String createIsolatedTableSql(String table) {
        requireIsolatedTable(table);
        var definitions = DEFINITION.columns().stream()
                .map(column -> "\"" + column.storageName() + "\" " + column.storageType().name()).toList();
        return "CREATE TABLE " + table + " (" + String.join(", ", definitions)
                + ") TIMESTAMP(timestamp) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, timestamp)";
    }

    public static void requireIsolatedTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalStateException("D016 execution requires java_d016_etf_share_<suffix>");
    }

    public static Instant requireObservation(Instant observation) {
        return com.zoutrankil.data.domain.temporal.TemporalValues.requirePrecision(
                observation, com.zoutrankil.data.domain.temporal.TemporalValues.Precision.MICROS);
    }
}
