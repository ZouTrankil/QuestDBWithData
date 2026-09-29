package com.zoutrankil.questdbwithdata.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** D026 isolated DEDUP=true compatibility definition; formal table DDL is audit-only. */
public final class MoneyflowDcDataset {
    public static final String ISOLATED_PREFIX = "java_d026_moneyflow_dc_";
    private static final TemporalContract TRADE_DATE = new TemporalContract(TemporalKind.BUSINESS_DATE,
            "BASIC", "calendar", "DAY", "Tushare trade_date represented at UTC midnight as a business-date carrier");
    private MoneyflowDcDataset() {}
    public static final DatasetDefinition DEFINITION = definition("moneyflow_dc");
    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("moneyflow_dc", 1, "tushare.moneyflow_dc", "moneyflow_dc_owner", table,
                ObjectKind.TABLE, columns(), List.of("ts_code", "trade_date"), List.of("ts_code", "trade_date"),
                "trade_date", Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE), List.of(),
                "D026 accepts writes only to an explicitly named isolated target. The audited external table has YEAR/WAL/DEDUP and (ts_code,trade_date); no formal DDL migration is created.");
    }
    public static List<Column> columns() {
        return List.of(
                col("ts_code", StorageType.SYMBOL, false, "Tushare SZ/SH stock code; complete business key"),
                new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                        "Tushare daily business date, UTC-midnight storage carrier", TRADE_DATE),
                col("name", StorageType.STRING, true, "Provider security name; source null is retained"),
                amount("pct_change", "Daily price change in percent"),
                amount("close", "Latest close price in CNY per share"),
                amount("net_amount", "Main-fund net inflow in 10,000 CNY"),
                amount("net_amount_rate", "Main-fund net inflow ratio in percent"),
                amount("buy_elg_amount", "Extra-large-order net inflow in 10,000 CNY"),
                amount("buy_elg_amount_rate", "Extra-large-order net inflow ratio in percent"),
                amount("buy_lg_amount", "Large-order net inflow in 10,000 CNY"),
                amount("buy_lg_amount_rate", "Large-order net inflow ratio in percent"),
                amount("buy_md_amount", "Medium-order net inflow in 10,000 CNY"),
                amount("buy_md_amount_rate", "Medium-order net inflow ratio in percent"),
                amount("buy_sm_amount", "Small-order net inflow in 10,000 CNY"),
                amount("buy_sm_amount_rate", "Small-order net inflow ratio in percent"));
    }
    private static Column col(String n, StorageType t, boolean nullable, String meaning) {
        return new Column(n, n, n, t, nullable, meaning, null);
    }
    private static Column amount(String n, String meaning) { return col(n, StorageType.DOUBLE, true, meaning + "; source null remains null"); }
    public static void requireIsolatedTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalStateException("D026 requires java_d026_moneyflow_dc_<explicit suffix> isolated target");
    }
    public static String createIsolatedTableSql(String table) {
        requireIsolatedTable(table);
        var cols = columns().stream().map(c -> "\"" + c.storageName() + "\" " + c.storageType().name()).toList();
        return "CREATE TABLE \"" + table + "\" (" + String.join(", ", cols)
                + ") TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, trade_date)";
    }
}
