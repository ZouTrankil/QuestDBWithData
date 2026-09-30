package com.zoutrankil.questdbwithdata.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** Formal D028 definition plus strict isolated WAL replacement target contract. */
public final class MarginAllDataset {
    public static final String ISOLATED_PREFIX = "java_d028_margin_all_";
    private static final TemporalContract TRADE_DATE = new TemporalContract(TemporalKind.BUSINESS_DATE,
            "BASIC", "calendar", "DAY", "Tushare trade date carried as UTC-midnight QuestDB timestamp");
    private MarginAllDataset() {}
    public static final DatasetDefinition DEFINITION = definition("margin_all");
    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("margin_all", 1, "tushare.margin", "margin_all_owner", table,
                ObjectKind.TABLE, columns(), List.of("trade_date", "exchange_id"), List.of(), "trade_date",
                Partition.YEAR, true, Set.of(Capability.READ), List.of(),
                "Audited target is YEAR/WAL/DEDUP=false with no physical upsert key. Natural identity is (trade_date,exchange_id); updates require journaled full-table stage replacement.");
    }
    public static DatasetDefinition isolatedWriteDefinition(String table) {
        requireIsolatedTable(table);
        return new DatasetDefinition("margin_all", 1, "tushare.margin", "margin_all_owner", table,
                ObjectKind.TABLE, columns(), List.of("trade_date", "exchange_id"), List.of(), "trade_date",
                Partition.YEAR, true, Set.of(Capability.READ, Capability.WAL_REPLACE), List.of(),
                "D028 isolated target retains YEAR/WAL/DEDUP=false. A fully receipt-verified staged snapshot is published by journaled rename; no upsert key is invented.");
    }
    public static List<Column> columns() {
        return List.of(new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                        "Tushare calendar trade date", TRADE_DATE),
                new Column("exchange_id", "exchange_id", "exchange_id", StorageType.SYMBOL, false,
                        "Tushare exchange aggregate identifier", null),
                number("rzye", "Margin financing balance, provider yuan"),
                number("rzmre", "Margin financing purchase amount, provider yuan"),
                number("rzche", "Margin financing repayment amount, provider yuan"),
                number("rqye", "Short-selling balance, provider yuan"),
                number("rqmcl", "Short-selling amount, provider units"),
                number("rzrqye", "Combined financing and short-selling balance, provider yuan"),
                number("rqyl", "Short-selling remaining quantity, provider units"));
    }
    private static Column number(String field, String meaning) {
        return new Column(field, field, field, StorageType.DOUBLE, false, meaning + "; source must be non-null and finite", null);
    }
    public static void requireIsolatedTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalArgumentException("D028 requires java_d028_margin_all_<explicit suffix> isolated target");
    }
    public static String createIsolatedTableSql(String table) {
        requireIsolatedTable(table);
        return "CREATE TABLE \"" + table + "\" (trade_date TIMESTAMP,exchange_id SYMBOL,rzye DOUBLE,rzmre DOUBLE,rzche DOUBLE,rqye DOUBLE,rqmcl DOUBLE,rzrqye DOUBLE,rqyl DOUBLE) TIMESTAMP(trade_date) PARTITION BY YEAR WAL";
    }
}
