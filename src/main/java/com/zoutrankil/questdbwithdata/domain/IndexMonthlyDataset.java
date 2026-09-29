package com.zoutrankil.questdbwithdata.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** D022 formal-schema and isolated compatibility contract; both retain DEDUP=false. */
public final class IndexMonthlyDataset {
    private IndexMonthlyDataset() {}
    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "Provider month-end trading observation carried at UTC midnight");
    private static final TemporalContract UPDATE_TIME = new TemporalContract(
            TemporalKind.INSTANT, "ISO_INSTANT", "UTC", "MICROS", "Frozen Java source-observation timestamp (technical metadata)");
    public static final DatasetDefinition DEFINITION = definition("index_monthly");

    /** Mirrors the audited external table: YEAR/WAL and no physical dedup key. */
    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("index_monthly", 1, "tushare.index_monthly",
                "index_monthly_owner", table, ObjectKind.TABLE, columns(),
                List.of("ts_code", "trade_date"), List.of(), "trade_date", Partition.YEAR, true,
                Set.of(Capability.READ), List.of("index"),
                "Audited formal schema is YEAR/WAL with DEDUP=false and no UPSERT key. Natural business identity is (provider-compatible ts_code, trade_date). Writes use a bounded complete-window stage, full-row verification and journaled target/backup table replacement; neither formal nor isolated targets are converted to DEDUP.");
    }

    /** A task-scoped writable acceptance contract with the same non-deduplicating layout. */
    public static DatasetDefinition isolatedWriteDefinition(String table) {
        requireIsolatedTableName(table);
        return new DatasetDefinition("index_monthly", 1, "tushare.index_monthly",
                "index_monthly_owner", table, ObjectKind.TABLE, columns(),
                List.of("ts_code", "trade_date"), List.of(), "trade_date", Partition.YEAR, true,
                Set.of(Capability.READ, Capability.WAL_REPLACE), List.of("index"),
                "D022 isolated acceptance target preserves YEAR/WAL/DEDUP=false. Corrections publish a complete bounded monthly window through a verified stage and recoverable target/backup replacement; direct in-place upsert is unsupported.");
    }

    public static List<Column> columns() {
        return List.of(
                new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                        "Historical monthly provider code; CSI entries intentionally use the .SH alias", null),
                new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                        "Provider month-end trading observation day carried at UTC midnight", TRADE_DATE),
                number("close", "Monthly close in index points"), number("open", "Monthly open in index points"),
                number("high", "Monthly high in index points"), number("low", "Monthly low in index points"),
                number("pre_close", "Previous close in index points"), number("change", "Point change"),
                number("pct_chg", "Percentage change in provider percentage points"),
                number("vol", "Provider volume in hands; no unit conversion"),
                number("amount", "Provider turnover in thousand yuan; no unit conversion"),
                new Column("derived:index_universe.layer", "layer", "layer", StorageType.SYMBOL, false,
                        "Frozen Python index research layer taxonomy", null),
                new Column("derived:index_universe.bucket", "bucket", "bucket", StorageType.SYMBOL, false,
                        "Frozen Python index research bucket taxonomy", null),
                new Column("derived:frozen_observed_at", "update_time", "update_time", StorageType.TIMESTAMP,
                        false, "Frozen source-observation instant at microsecond precision", UPDATE_TIME));
    }
    private static Column number(String name, String meaning) {
        return new Column(name, name, name, StorageType.DOUBLE, true, meaning, null);
    }
    public static void requireIsolatedTableName(String table) {
        DatasetDefinition.identifier(table);
        String prefix = "java_d022_index_monthly_";
        if (!table.startsWith(prefix) || table.length() <= prefix.length())
            throw new IllegalArgumentException("D022 isolated target required; the formal index_monthly table is external and has no dedup key");
    }
    public static String createIsolatedTableSql(String table) {
        requireIsolatedTableName(table);
        return "CREATE TABLE \"" + table + "\" (ts_code SYMBOL, trade_date TIMESTAMP, close DOUBLE, open DOUBLE, high DOUBLE, low DOUBLE, "
                + "pre_close DOUBLE, change DOUBLE, pct_chg DOUBLE, vol DOUBLE, amount DOUBLE, layer SYMBOL, bucket SYMBOL, update_time TIMESTAMP) "
                + "TIMESTAMP(trade_date) PARTITION BY YEAR WAL";
    }
}
