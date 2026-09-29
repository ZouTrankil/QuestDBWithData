package com.zoutrankil.questdbwithdata.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** D023 preserves the audited external YEAR/WAL/no-dedup schema. */
public final class DcIndexDataset {
    public static final String ISOLATED_PREFIX = "java_d023_dc_index_";
    public static final List<String> NUMERIC_FIELDS = List.of("pct_change", "leading_pct", "total_mv", "turnover_rate", "up_num", "down_num");
    private static final TemporalContract TRADE_DATE = new TemporalContract(TemporalKind.BUSINESS_DATE,
            "BASIC", "calendar", "DAY", "Provider trading date carried at UTC midnight; not an instant");
    private DcIndexDataset() {}
    public static final DatasetDefinition DEFINITION = definition("dc_index");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("dc_index", 1, "tushare.dc_index", "dc_index_owner", table,
                ObjectKind.TABLE, List.of(
                new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false, "Provider DC board code", null),
                new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false, "Provider date stored as a calendar carrier", TRADE_DATE),
                new Column("name", "name", "name", StorageType.STRING, true, "Board display name", null),
                new Column("leading", "leading", "leading", StorageType.STRING, true, "Leading constituent display name", null),
                new Column("leading_code", "leading_code", "leading_code", StorageType.STRING, true, "Leading constituent security code", null),
                new Column("pct_change", "pct_change", "pct_change", StorageType.DOUBLE, true, "Source percentage-unit board change; unscaled", null),
                new Column("leading_pct", "leading_pct", "leading_pct", StorageType.DOUBLE, true, "Source percentage-unit leading security change; unscaled", null),
                new Column("total_mv", "total_mv", "total_mv", StorageType.DOUBLE, true, "Python model describes 万元; exact live unit must be confirmed against source", null),
                new Column("turnover_rate", "turnover_rate", "turnover_rate", StorageType.DOUBLE, true, "Source turnover value; no conversion", null),
                new Column("up_num", "up_num", "up_num", StorageType.INT, true, "Source advancing constituent count", null),
                new Column("down_num", "down_num", "down_num", StorageType.INT, true, "Source declining constituent count", null)),
                List.of("ts_code", "trade_date"), List.of(), "trade_date", Partition.YEAR, true,
                Set.of(Capability.READ, Capability.WAL_REPLACE), List.of("exchange_calendar"),
                "Audited external physical table is YEAR partitioned, WAL enabled and DEDUP=false. Natural identity is (ts_code,trade_date), but QuestDB must not deduplicate; bounded authoritative date windows use a separately verified DEDUP=false stage and journaled table replacement. No formal-schema DDL/migration is performed by D023.");
    }
    public static void requireIsolatedTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalStateException("D023 requires java_d023_dc_index_<suffix> isolated target");
    }
    public static String createIsolatedTableSql(String table) {
        requireIsolatedTable(table);
        var columns = DEFINITION.columns().stream().map(c -> "\"" + c.storageName() + "\" " + c.storageType().name()).toList();
        return "CREATE TABLE \"" + table + "\" (" + String.join(", ", columns)
                + ") TIMESTAMP(trade_date) PARTITION BY YEAR WAL";
    }
}
