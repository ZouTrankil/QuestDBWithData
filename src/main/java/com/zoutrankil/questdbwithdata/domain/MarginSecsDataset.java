package com.zoutrankil.questdbwithdata.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** D030 keeps the audited YEAR/WAL/DEDUP target and its (trade_date, ts_code) upsert key. */
public final class MarginSecsDataset {
    public static final String ISOLATED_PREFIX = "java_d030_margin_secs_";
    private static final TemporalContract TRADE_DATE = new TemporalContract(TemporalKind.BUSINESS_DATE,
            "BASIC", "calendar", "DAY", "Tushare trade date carried as UTC-midnight calendar timestamp");
    private MarginSecsDataset() {}
    public static final DatasetDefinition DEFINITION = definition("margin_secs");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("margin_secs", 1, "tushare.margin_secs", "margin_secs_owner", table,
                ObjectKind.TABLE, columns(), List.of("trade_date", "ts_code"), List.of("trade_date", "ts_code"),
                "trade_date", Partition.YEAR, true, Set.of(Capability.READ), List.of(),
                "Audited formal layout is YEAR/WAL/DEDUP=true with UPSERT KEY (trade_date,ts_code). The same pair is the daily membership natural key.");
    }

    public static DatasetDefinition isolatedWriteDefinition(String table) {
        requireIsolatedTable(table);
        return new DatasetDefinition("margin_secs", 1, "tushare.margin_secs", "margin_secs_owner", table,
                ObjectKind.TABLE, columns(), List.of("trade_date", "ts_code"), List.of("trade_date", "ts_code"),
                "trade_date", Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE), List.of(),
                "D030 isolated target preserves YEAR/WAL/DEDUP=true and its audited two-column upsert key.");
    }

    public static List<Column> columns() {
        return List.of(new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                        "Tushare open-market business date", TRADE_DATE),
                new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                        "Six-digit security code with .SH/.SZ/.BJ suffix", null),
                new Column("name", "name", "name", StorageType.STRING, true,
                        "Provider security name; source null remains null", null),
                new Column("exchange", "exchange", "exchange", StorageType.SYMBOL, false,
                        "Provider exchange label, preserved without inferred remapping", null));
    }

    public static void requireIsolatedTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalArgumentException("D030 requires java_d030_margin_secs_<explicit suffix> isolated target");
    }

    public static String createIsolatedTableSql(String table) {
        requireIsolatedTable(table);
        return "CREATE TABLE \"" + table + "\" (trade_date TIMESTAMP,ts_code SYMBOL,name STRING,exchange SYMBOL) "
                + "TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(trade_date,ts_code)";
    }
}
