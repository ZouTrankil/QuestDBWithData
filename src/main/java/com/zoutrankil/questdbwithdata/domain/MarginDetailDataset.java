package com.zoutrankil.questdbwithdata.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** Audited margin_detail schema plus a write contract restricted to isolated D029 tables. */
public final class MarginDetailDataset {
    public static final String ISOLATED_PREFIX = "java_d029_margin_detail_";
    private static final TemporalContract TRADE_DATE = new TemporalContract(TemporalKind.BUSINESS_DATE,
            "BASIC", "calendar", "DAY", "Tushare trade_date stored as UTC-midnight timestamp carrier");
    private MarginDetailDataset() {}
    public static final DatasetDefinition DEFINITION = definition("margin_detail");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("margin_detail", 1, "tushare.margin_detail", "margin_detail_owner", table,
                ObjectKind.TABLE, columns(), List.of("ts_code", "trade_date"), List.of("trade_date", "ts_code"),
                "trade_date", Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE), List.of(),
                "Audited physical schema is YEAR/WAL/DEDUP=true with UPSERT KEYS(ts_code,trade_date). D029 writes only an explicitly isolated table; formal production DDL remains audit-only.");
    }

    public static List<Column> columns() {
        return List.of(
                new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                        "Tushare trade date represented at UTC midnight", TRADE_DATE),
                new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                        "Tushare mainland security code; second part of physical upsert key and first part of Python natural key", null),
                col("name", StorageType.STRING, true, "Provider security name; null is preserved"),
                num("rzye", false, "Financing balance in CNY; must be nonnegative"),
                num("rzmre", false, "Financing purchase amount in CNY; must be nonnegative"),
                num("rzche", true, "Financing repayment amount in provider CNY units"),
                num("rqye", true, "Short-selling balance in CNY; nonnegative when present"),
                num("rqyl", true, "Short-selling remaining quantity in provider shares/units"),
                num("rqchl", true, "Short-selling repayment quantity in provider shares/units"),
                num("rqmcl", true, "Short-selling amount in provider shares/units; retain source scale"),
                num("rzrqye", true, "Combined financing and short-selling balance in CNY; nonnegative when present"));
    }
    private static Column col(String name, StorageType type, boolean nullable, String meaning) {
        return new Column(name, name, name, type, nullable, meaning, null);
    }
    private static Column num(String name, boolean nullable, String meaning) {
        return col(name, StorageType.DOUBLE, nullable, meaning + "; finite source values are not rescaled");
    }
    public static void requireIsolatedTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalArgumentException("D029 requires java_d029_margin_detail_<explicit suffix> isolated target");
    }
    public static String createIsolatedTableSql(String table) {
        requireIsolatedTable(table);
        return "CREATE TABLE \"" + table + "\" (trade_date TIMESTAMP,ts_code SYMBOL,name STRING,rzye DOUBLE,rzmre DOUBLE,rzche DOUBLE,rqye DOUBLE,rqyl DOUBLE,rqchl DOUBLE,rqmcl DOUBLE,rzrqye DOUBLE) "
                + "TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(trade_date, ts_code)";
    }
}
