package com.zoutrankil.questdbwithdata.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** D025 typed contract for the externally managed moneyflow_ths table. */
public final class MoneyflowThsDataset {
    public static final String ISOLATED_PREFIX = "java_d025_moneyflow_ths_";
    private static final TemporalContract TRADE_DATE = new TemporalContract(TemporalKind.BUSINESS_DATE,
            "BASIC", "calendar", "DAY", "Tushare trade date carried at UTC midnight; not an event instant");
    private MoneyflowThsDataset() {}

    public static final DatasetDefinition DEFINITION = definition("moneyflow_ths");
    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("moneyflow_ths", 1, "tushare.moneyflow_ths", "moneyflow_ths_owner", table,
                ObjectKind.TABLE, columns(), List.of("ts_code", "trade_date"), List.of("ts_code", "trade_date"),
                "trade_date", Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE),
                List.of("exchange_calendar"),
                "Audited external object is YEAR/WAL/DEDUP with UPSERT KEY(ts_code,trade_date). "
                        + "No formal-table DDL is applied; the D025 owner requires an explicit isolated target. "
                        + "Amounts are in 10,000 CNY and percentage fields are unchanged provider values.");
    }
    public static List<Column> columns() {
        return List.of(
                new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                        "Tushare six-digit stock code with exchange suffix", null),
                new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                        "Tushare business date stored as UTC-midnight carrier",
                        TRADE_DATE),
                text("name", "Provider stock name; null remains null"),
                number("pct_change", "Price percentage change in provider percent units"),
                number("latest", "Latest price in provider currency units"),
                number("net_amount", "Net flow amount in 10,000 CNY"),
                number("net_d5_amount", "Five-day net flow amount in 10,000 CNY"),
                number("buy_lg_amount", "Large-order net buy amount in 10,000 CNY"),
                number("buy_lg_amount_rate", "Large-order net buy percentage in provider percent units"),
                number("buy_md_amount", "Medium-order net buy amount in 10,000 CNY"),
                number("buy_md_amount_rate", "Medium-order net buy percentage in provider percent units"),
                number("buy_sm_amount", "Small-order net buy amount in 10,000 CNY"),
                number("buy_sm_amount_rate", "Small-order net buy percentage in provider percent units"));
    }
    public static void requireIsolatedTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalStateException("D025 requires java_d025_moneyflow_ths_<explicit suffix> isolated target");
    }
    public static String createIsolatedTableSql(String table) {
        requireIsolatedTable(table);
        var columns = columns().stream().map(c -> "\"" + c.storageName() + "\" " + c.storageType().name()).toList();
        return "CREATE TABLE \"" + table + "\" (" + String.join(", ", columns)
                + ") TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, trade_date)";
    }
    private static Column text(String name, String meaning) {
        return new Column(name, name, name, StorageType.STRING, true, meaning, null);
    }
    private static Column number(String name, String meaning) {
        return new Column(name, name, name, StorageType.DOUBLE, true, meaning, null);
    }
}
