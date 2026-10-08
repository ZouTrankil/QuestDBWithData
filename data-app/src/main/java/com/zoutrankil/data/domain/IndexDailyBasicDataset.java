package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D020 contract for the externally owned index_daily_basic table. */
public final class IndexDailyBasicDataset {
    private IndexDailyBasicDataset() {}
    private static final TemporalContract TRADE_DATE = new TemporalContract(
            TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "Index trade date carried at UTC midnight");
    public static final DatasetDefinition DEFINITION = definition("index_daily_basic");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("index_daily_basic", 1, "tushare.index_dailybasic",
                "index_daily_basic_owner", table, ObjectKind.TABLE, columns(),
                List.of("ts_code", "trade_date"), List.of("ts_code", "trade_date"), "trade_date",
                Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE), List.of(),
                "Preserve audited YEAR/WAL/DEDUP layout and (ts_code,trade_date) physical key. "
                        + "trade_date is a calendar business date carried at UTC midnight. No formal-table DDL is applied because this table is externally owned.");
    }
    public static List<Column> columns() {
        return List.of(
                new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                        "Canonical Tushare core index code", null),
                new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                        "Index trading business date, UTC-midnight carrier", TRADE_DATE),
                number("total_mv", "Total market capitalization in yuan"),
                number("float_mv", "Tradable market capitalization in yuan"),
                number("total_share", "Total shares in shares"),
                number("float_share", "Tradable shares in shares"),
                number("free_share", "Free-float shares in shares"),
                number("turnover_rate", "Turnover rate, endpoint-native percentage value"),
                number("turnover_rate_f", "Free-float turnover rate, endpoint-native percentage value"),
                number("pe", "Price/earnings ratio"), number("pe_ttm", "Trailing-twelve-month price/earnings ratio"),
                number("pb", "Price/book ratio"));
    }
    private static Column number(String name, String meaning) {
        return new Column(name, name, name, StorageType.DOUBLE, true, meaning, null);
    }
    /** Create only a task-scoped isolated target; never a migration for the production-owned table. */
    public static String createIsolatedTableSql(String table) {
        DatasetDefinition.identifier(table);
        String prefix = "java_d020_index_daily_basic_";
        if (!table.startsWith(prefix) || table.length() <= prefix.length())
            throw new IllegalArgumentException("D020 isolated table name required");
        return "CREATE TABLE \"" + table + "\" (ts_code SYMBOL, trade_date TIMESTAMP, total_mv DOUBLE, float_mv DOUBLE, "
                + "total_share DOUBLE, float_share DOUBLE, free_share DOUBLE, turnover_rate DOUBLE, turnover_rate_f DOUBLE, "
                + "pe DOUBLE, pe_ttm DOUBLE, pb DOUBLE) TIMESTAMP(trade_date) PARTITION BY YEAR WAL "
                + "DEDUP UPSERT KEYS(ts_code, trade_date)";
    }
}
