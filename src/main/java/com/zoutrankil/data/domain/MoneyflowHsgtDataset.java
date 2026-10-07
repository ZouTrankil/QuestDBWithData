package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D027 preserves the audited DAY/WAL/non-DEDUP schema; updates publish a verified full-table stage. */
public final class MoneyflowHsgtDataset {
    public static final String ISOLATED_PREFIX = "java_d027_moneyflow_hsgt_";
    private static final TemporalContract TRADE_DATE = new TemporalContract(TemporalKind.BUSINESS_DATE,
            "BASIC", "calendar", "DAY", "Provider trade date stored as a UTC-midnight calendar carrier");
    private MoneyflowHsgtDataset() {}
    public static final DatasetDefinition DEFINITION = definition("moneyflow_hsgt");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("moneyflow_hsgt", 1, "tushare.moneyflow_hsgt", "moneyflow_hsgt_owner",
                table, ObjectKind.TABLE, columns(), List.of("trade_date"), List.of(), "trade_date", Partition.DAY,
                true, Set.of(Capability.READ), List.of(),
                "Audited formal layout is DAY/WAL/DEDUP=false. Natural identity is one aggregate per trade_date, but it is not a physical UPSERT key; writes require verified full-table stage replacement.");
    }

    public static DatasetDefinition isolatedWriteDefinition(String table) {
        requireIsolatedTable(table);
        return admittedWriteDefinition(table);
    }

    /** Formal writes are stage replacement only; layout and natural identity are unchanged. */
    public static DatasetDefinition admittedWriteDefinition(String table) {
        requireAdmittedTable(table);
        return new DatasetDefinition("moneyflow_hsgt", 1, "tushare.moneyflow_hsgt", "moneyflow_hsgt_owner",
                table, ObjectKind.TABLE, columns(), List.of("trade_date"), List.of(), "trade_date", Partition.DAY,
                true, Set.of(Capability.READ, Capability.WAL_REPLACE), List.of(),
                "D027 acceptance target retains DAY/WAL/DEDUP=false. Corrections publish a bounded full-table snapshot from a verified stage; no DEDUP key is invented.");
    }

    public static List<Column> columns() {
        return List.of(new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                        "Tushare trade date; UTC-midnight business-date carrier", TRADE_DATE),
                number("ggt_ss", "港股通（上海）资金汇总，million CNY"),
                number("ggt_sz", "港股通（深圳）资金汇总，million CNY"),
                number("hgt", "沪股通资金汇总，million CNY"),
                number("sgt", "深股通资金汇总，million CNY"),
                number("north_money", "Total northbound aggregate, million CNY"),
                number("south_money", "Total southbound aggregate, million CNY"));
    }
    private static Column number(String field, String meaning) {
        return new Column(field, field, field, StorageType.DOUBLE, true, meaning + "; source null remains null", null);
    }
    public static void requireIsolatedTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalArgumentException("D027 requires java_d027_moneyflow_hsgt_<explicit suffix> isolated target");
    }
    public static void requireAdmittedTable(String table) {
        if ("moneyflow_hsgt".equals(table)) return;
        requireIsolatedTable(table);
    }
    public static String createIsolatedTableSql(String table) {
        requireIsolatedTable(table);
        return "CREATE TABLE \"" + table + "\" (trade_date TIMESTAMP,ggt_ss DOUBLE,ggt_sz DOUBLE,hgt DOUBLE,sgt DOUBLE,north_money DOUBLE,south_money DOUBLE) TIMESTAMP(trade_date) PARTITION BY DAY WAL";
    }
}
