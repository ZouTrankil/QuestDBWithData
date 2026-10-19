package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Formal D031 definition plus an isolated YEAR/WAL full-snapshot replacement target. */
public final class MarginZrzDataset {
    public static final String ISOLATED_PREFIX = "java_d031_margin_zrz_";
    private static final TemporalContract TRADE_DATE = new TemporalContract(TemporalKind.BUSINESS_DATE,
            "BASIC", "calendar", "DAY", "Tushare trade_date carried as UTC-midnight QuestDB timestamp");
    private MarginZrzDataset() {}
    public static final DatasetDefinition DEFINITION = definition("margin_zrz");
    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("margin_zrz", 1, "tushare.slb_len", "margin_zrz_owner", table,
                ObjectKind.TABLE, columns(), List.of("trade_date"), List.of(), "trade_date",
                Partition.YEAR, true, Set.of(Capability.READ), List.of(),
                "Audited physical object is YEAR/WAL/DEDUP=false. Natural key is trade_date; updates require journaled full-table stage replacement.");
    }
    public static DatasetDefinition isolatedWriteDefinition(String table) {
        requireIsolatedTable(table);
        return new DatasetDefinition("margin_zrz", 1, "tushare.slb_len", "margin_zrz_owner", table,
                ObjectKind.TABLE, columns(), List.of("trade_date"), List.of(), "trade_date",
                Partition.YEAR, true, Set.of(Capability.READ, Capability.WAL_REPLACE), List.of(),
                "D031 isolated target retains YEAR/WAL/DEDUP=false; a fully receipt-verified stage is published by journaled rename.");
    }
    public static List<Column> columns() {
        return List.of(new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                        "Tushare slb_len trade date", TRADE_DATE),
                number("ob", "Opening balance (100 million yuan)"),
                number("auc_amount", "Auction transaction amount (100 million yuan)"),
                number("repo_amount", "Re-lending transaction amount (100 million yuan)"),
                number("repay_amount", "Repayment amount (100 million yuan)"),
                number("cb", "Closing balance (100 million yuan)"));
    }
    private static Column number(String field, String meaning) {
        return new Column(field, field, field, StorageType.DOUBLE, true,
                meaning + "; provider model is Optional[float], explicit null is preserved", null);
    }
    public static void requireIsolatedTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalArgumentException("D031 requires java_d031_margin_zrz_<explicit suffix> isolated target");
    }
    public static String createIsolatedTableSql(String table) {
        requireIsolatedTable(table);
        return "CREATE TABLE \"" + table + "\" (trade_date TIMESTAMP,ob DOUBLE,auc_amount DOUBLE,repo_amount DOUBLE,repay_amount DOUBLE,cb DOUBLE) TIMESTAMP(trade_date) PARTITION BY YEAR WAL";
    }
}
