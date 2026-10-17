package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D024 mirrors the audited YEAR/WAL/DEDUP moneyflow layout. */
public final class MoneyflowDataset {
    public static final String ISOLATED_PREFIX = "java_d024_moneyflow_";
    private static final TemporalContract TRADE_DATE = new TemporalContract(TemporalKind.BUSINESS_DATE,
            "BASIC", "calendar", "DAY", "Tushare trade date carried at UTC midnight, not an instant");
    private MoneyflowDataset() {}
    public static void requireExecutionTable(String table) {
        if (!"moneyflow".equals(table)) requireIsolatedTable(table);
    }
    public static final DatasetDefinition DEFINITION = definition("moneyflow");
    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("moneyflow", 1, "tushare.moneyflow", "moneyflow_owner", table,
                ObjectKind.TABLE, columns(), List.of("ts_code", "trade_date"), List.of("ts_code", "trade_date"),
                "trade_date", Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE),
                List.of("exchange_calendar"), "Audited external table uses YEAR/WAL/DEDUP and full (ts_code,trade_date) UPSERT key. Amounts stay in source 万元 and volumes in 手; no formal-table migration is performed by D024.");
    }
    public static List<Column> columns() {
        return List.of(
                col("ts_code", StorageType.SYMBOL, false, "Tushare SZ/SH A-share code"),
                new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false, "Tushare business date as UTC-midnight carrier", TRADE_DATE),
                vol("buy_sm_vol", "Small-order buy volume in hands"), amount("buy_sm_amount", "Small-order buy amount in 10,000 CNY"),
                vol("sell_sm_vol", "Small-order sell volume in hands"), amount("sell_sm_amount", "Small-order sell amount in 10,000 CNY"),
                vol("buy_md_vol", "Medium-order buy volume in hands"), amount("buy_md_amount", "Medium-order buy amount in 10,000 CNY"),
                vol("sell_md_vol", "Medium-order sell volume in hands"), amount("sell_md_amount", "Medium-order sell amount in 10,000 CNY"),
                vol("buy_lg_vol", "Large-order buy volume in hands"), amount("buy_lg_amount", "Large-order buy amount in 10,000 CNY"),
                vol("sell_lg_vol", "Large-order sell volume in hands"), amount("sell_lg_amount", "Large-order sell amount in 10,000 CNY"),
                vol("buy_elg_vol", "Extra-large-order buy volume in hands"), amount("buy_elg_amount", "Extra-large-order buy amount in 10,000 CNY"),
                vol("sell_elg_vol", "Extra-large-order sell volume in hands"), amount("sell_elg_amount", "Extra-large-order sell amount in 10,000 CNY"),
                new Column("net_mf_vol", "net_mf_vol", "net_mf_vol", StorageType.LONG, false, "Signed net active-order volume in hands; Python fills source null with zero", null),
                amount("net_mf_amount", "Signed net active-order amount in 10,000 CNY"));
    }
    private static Column col(String n, StorageType t, boolean nullable, String meaning) { return new Column(n,n,n,t,nullable,meaning,null); }
    private static Column vol(String n, String meaning) { return col(n,StorageType.LONG,false,meaning+"; Python fills source null with zero"); }
    private static Column amount(String n, String meaning) { return col(n,StorageType.DOUBLE,true,meaning+"; source null remains null"); }
    public static void requireIsolatedTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalStateException("D024 requires java_d024_moneyflow_<explicit suffix> isolated target");
    }
    public static String createIsolatedTableSql(String table) {
        requireIsolatedTable(table);
        var columns = columns().stream().map(c -> "\"" + c.storageName() + "\" " + c.storageType().name()).toList();
        return "CREATE TABLE \"" + table + "\" (" + String.join(", ", columns)
                + ") TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, trade_date)";
    }
}
