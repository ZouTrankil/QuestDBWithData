package com.zoutrankil.data.domain.fund;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.data.domain.shared.policy.IsolatedTablePolicy;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** D015 definition for the externally owned, audited etf_adj table. */
public final class EtfAdjDataset {
    private EtfAdjDataset() {}

    private static final TemporalContract TRADE_DATE =
            new TemporalContract(
                    TemporalKind.BUSINESS_DATE,
                    "BASIC",
                    "calendar",
                    "DAY",
                    "Tushare fund adjustment trade date");

    public static final DatasetDefinition DEFINITION = definition("etf_adj");

    public static DatasetDefinition definition(String table) {
        return definition(table, CnTradeDateLayout.LEGACY_V1);
    }

    public static DatasetDefinition definition(String table, CnTradeDateLayout layout) {
        DatasetDefinition.identifier(table);
        Objects.requireNonNull(layout);
        String dateColumn = layout.physicalDateColumn("etf_adj");
        return new DatasetDefinition(
                "etf_adj",
                layout.schemaVersion(),
                "tushare.fund_adj",
                "etf_adj_owner",
                table,
                ObjectKind.TABLE,
                columns(layout),
                List.of("ts_code", "trade_date"),
                List.of("ts_code", dateColumn),
                dateColumn,
                Partition.YEAR,
                true,
                Set.of(Capability.READ, Capability.WRITE),
                List.of("exchange_calendar"),
                layout == CnTradeDateLayout.LEGACY_V1
                        ? "Retain YEAR/WAL/dedup (ts_code,timestamp) with TIMESTAMP microsecond storage. Source trade_date maps explicitly to a UTC-midnight business-date carrier; no precision loss and no formal-table DDL."
                        : "v2 将指定列及去重键日期名明确为 trade_date；保留 YEAR/WAL、微秒精度和 UTC 午夜日期载体，不自动迁移正式表。");
    }

    public static List<Column> columns() {
        return columns(CnTradeDateLayout.LEGACY_V1);
    }

    public static List<Column> columns(CnTradeDateLayout layout) {
        Objects.requireNonNull(layout);
        return List.of(
                new Column(
                        "ts_code",
                        "ts_code",
                        "ts_code",
                        StorageType.SYMBOL,
                        false,
                        "Tushare fund identity with exchange suffix",
                        null),
                new Column(
                        "trade_date",
                        "trade_date",
                        layout.physicalDateColumn("etf_adj"),
                        StorageType.TIMESTAMP,
                        false,
                        "Business trading date stored as UTC-midnight carrier, not an instant",
                        TRADE_DATE),
                new Column(
                        "adj_factor",
                        "adj_factor",
                        "adj_factor",
                        StorageType.DOUBLE,
                        true,
                        "Unscaled Tushare fund adjustment factor",
                        null));
    }

    /** DDL for a task-owned acceptance table only; never applied to the externally owned formal table. */
    public static String createIsolatedTableSql(String table) {
        return createIsolatedTableSql(table, CnTradeDateLayout.LEGACY_V1);
    }

    public static String createIsolatedTableSql(String table, CnTradeDateLayout layout) {
        DatasetDefinition.identifier(table);
        String prefix = "java_d015_etf_adj_";
        if (!table.startsWith(prefix) || table.length() <= prefix.length())
            throw new IllegalArgumentException("D015 isolated table name required");
        var definitions = new ArrayList<String>();
        for (var column : columns(layout))
            definitions.add("\"" + column.storageName() + "\" " + column.storageType().name());
        return "CREATE TABLE "
                + table
                + " ("
                + String.join(", ", definitions)
                + ") TIMESTAMP("
                + layout.physicalDateColumn("etf_adj")
                + ") PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, "
                + layout.physicalDateColumn("etf_adj")
                + ")";
    }

    /** Admits the existing formal table or the original isolated execution namespace. */
    public static void requireExecutionTable(String table) {
        if (!"etf_adj".equals(table)) IsolatedTablePolicy.ETF_ADJ.require(table);
    }
}
