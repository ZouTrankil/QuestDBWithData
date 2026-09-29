package com.zoutrankil.questdbwithdata.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** D021 contract for the externally owned index_weight table. */
public final class IndexWeightDataset {
    private IndexWeightDataset() {}
    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "Index constituent snapshot date carried at UTC midnight");
    private static final TemporalContract UPDATE_TIME = new TemporalContract(
            TemporalKind.INSTANT, "ISO_INSTANT", "UTC", "MICROS", "Frozen Java source-observation time");
    public static final DatasetDefinition DEFINITION = definition("index_weight");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("index_weight", 1, "csindex.oss-xls+tushare.index_weight",
                "index_weight_owner", table, ObjectKind.TABLE, columns(),
                List.of("index_code", "con_code", "trade_date"),
                List.of("index_code", "con_code", "trade_date"), "trade_date", Partition.YEAR, true,
                Set.of(Capability.READ, Capability.WRITE), List.of("index", "stock_detail_info"),
                "Preserve audited YEAR/WAL/DEDUP and (index_code,con_code,trade_date) physical key. "
                        + "The audit records exchange as SYMBOL while the legacy Python model declares STRING; isolated Java DDL follows observed physical schema. "
                        + "No formal-table migration is applied because index_weight is externally owned.");
    }
    public static List<Column> columns() {
        return List.of(
                new Column("index_code", "index_code", "index_code", StorageType.SYMBOL, false,
                        "Six digit canonical index identity without exchange suffix", null),
                new Column("con_code", "con_code", "con_code", StorageType.SYMBOL, false,
                        "Canonical exchange-qualified constituent code", null),
                new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                        "Constituent-weight snapshot business date carried at UTC midnight", TRADE_DATE),
                text("index_name", "Chinese index name; optional on the Tushare history route"),
                text("index_name_en", "English index name; optional on the Tushare history route"),
                text("con_name", "Chinese constituent name; optional when source omits enrichment"),
                text("con_name_en", "English constituent name; optional on the Tushare history route"),
                new Column("exchange", "exchange", "exchange", StorageType.SYMBOL, true,
                        "Constituent exchange name; SYMBOL follows audited QuestDB schema despite legacy Python STRING declaration", null),
                new Column("exchange_en", "exchange_en", "exchange_en", StorageType.STRING, true,
                        "English constituent exchange name", null),
                new Column("weight", "weight", "weight", StorageType.DOUBLE, false,
                        "Constituent weight expressed in percentage points", null),
                new Column("derived:frozen_observed_at", "update_time", "update_time", StorageType.TIMESTAMP, false,
                        "Frozen UTC source observation time at microsecond precision", UPDATE_TIME));
    }
    private static Column text(String name, String meaning) {
        return new Column(name, name, name, StorageType.STRING, true, meaning, null);
    }
    /** DDL is restricted to an explicit D021 isolated table. */
    public static String createIsolatedTableSql(String table) {
        DatasetDefinition.identifier(table);
        String prefix = "java_d021_index_weight_";
        if (!table.startsWith(prefix) || table.length() <= prefix.length())
            throw new IllegalArgumentException("D021 isolated table name required");
        return "CREATE TABLE \"" + table + "\" (index_code SYMBOL, con_code SYMBOL, trade_date TIMESTAMP, "
                + "index_name STRING, index_name_en STRING, con_name STRING, con_name_en STRING, "
                + "exchange SYMBOL, exchange_en STRING, weight DOUBLE, update_time TIMESTAMP) "
                + "TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(index_code, con_code, trade_date)";
    }
}
