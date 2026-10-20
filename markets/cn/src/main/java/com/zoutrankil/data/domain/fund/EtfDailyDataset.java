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
import com.zoutrankil.market.cn.model.CnNumericContracts;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** D014 mapping for the externally owned, audited etf_daily table. */
public final class EtfDailyDataset {
    private EtfDailyDataset() {}

    private static final TemporalContract TRADE_DATE =
            new TemporalContract(
                    TemporalKind.BUSINESS_DATE,
                    "BASIC",
                    "calendar",
                    "DAY",
                    "Tushare exchange trade date");

    public static final DatasetDefinition DEFINITION = definition("etf_daily");

    public static DatasetDefinition definition(String table) {
        return definition(table, CnTradeDateLayout.LEGACY_V1);
    }

    /** 布局只改物理日期名；纳秒载体、行情单位和完整键含义不变。 */
    public static DatasetDefinition definition(String table, CnTradeDateLayout layout) {
        String dateColumn = layout.physicalDateColumn("etf_daily");
        DatasetDefinition.identifier(table);
        return new DatasetDefinition(
                "etf_daily",
                layout.schemaVersion(),
                "tushare.fund_daily",
                "etf_daily_owner",
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
                        ? "Retain YEAR/WAL/dedup (ts_code,timestamp) with TIMESTAMP_NS storage. Source trade_date maps explicitly to business date and the UTC-midnight nanosecond carrier; no precision loss or formal-table DDL."
                        : "交易日布局 v2 保留 YEAR/WAL、TIMESTAMP_NS 纳秒载体和完整业务键；物理列改为 trade_date，不自动迁移正式表。");
    }

    public static List<Column> columns() {
        return columns(CnTradeDateLayout.LEGACY_V1);
    }

    public static List<Column> columns(CnTradeDateLayout layout) {
        return CnNumericContracts.attach(
                "fund_daily",
                List.of(
                        new Column(
                                "ts_code",
                                "ts_code",
                                "ts_code",
                                StorageType.SYMBOL,
                                false,
                                "Tushare instrument identity with exchange suffix",
                                null),
                        new Column(
                                "trade_date",
                                "trade_date",
                                layout.physicalDateColumn("etf_daily"),
                                StorageType.TIMESTAMP_NS,
                                false,
                                "Business trading date stored as UTC-midnight carrier, not an instant",
                                TRADE_DATE),
                        new Column(
                                "pre_close",
                                "pre_close",
                                "pre_close",
                                StorageType.DOUBLE,
                                true,
                                "Previous close in yuan",
                                null),
                        new Column(
                                "open",
                                "open",
                                "open",
                                StorageType.DOUBLE,
                                true,
                                "Open in yuan",
                                null),
                        new Column(
                                "high",
                                "high",
                                "high",
                                StorageType.DOUBLE,
                                true,
                                "High in yuan",
                                null),
                        new Column(
                                "low", "low", "low", StorageType.DOUBLE, true, "Low in yuan", null),
                        new Column(
                                "close",
                                "close",
                                "close",
                                StorageType.DOUBLE,
                                true,
                                "Close in yuan",
                                null),
                        new Column(
                                "change",
                                "change",
                                "change",
                                StorageType.DOUBLE,
                                true,
                                "Change in yuan",
                                null),
                        new Column(
                                "pct_chg",
                                "pct_chg",
                                "pct_chg",
                                StorageType.DOUBLE,
                                true,
                                "Percent points, not a fraction",
                                null),
                        new Column(
                                "vol",
                                "vol",
                                "vol",
                                StorageType.DOUBLE,
                                true,
                                "Volume in lots",
                                null),
                        new Column(
                                "amount",
                                "amount",
                                "amount",
                                StorageType.DOUBLE,
                                true,
                                "Turnover in thousand yuan",
                                null)));
    }

    /** DDL for a task-owned acceptance table only; never applied to the externally owned formal table. */
    public static String createIsolatedTableSql(String table) {
        return createIsolatedTableSql(table, CnTradeDateLayout.LEGACY_V1);
    }

    public static String createIsolatedTableSql(String table, CnTradeDateLayout layout) {
        String dateColumn = layout.physicalDateColumn("etf_daily");
        DatasetDefinition.identifier(table);
        if (!table.startsWith("java_d014_etf_daily_")
                || table.length() <= "java_d014_etf_daily_".length())
            throw new IllegalArgumentException("D014 isolated table name required");
        var definitions = new ArrayList<String>();
        for (var column : columns(layout))
            definitions.add("\"" + column.storageName() + "\" " + column.storageType().name());
        return "CREATE TABLE "
                + table
                + " ("
                + String.join(", ", definitions)
                + ") TIMESTAMP("
                + dateColumn
                + ") PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, "
                + dateColumn
                + ")";
    }

    /** Admits the existing formal table or the original isolated execution namespace. */
    public static void requireExecutionTable(String table) {
        if (!"etf_daily".equals(table)) IsolatedTablePolicy.ETF_DAILY.require(table);
    }
}
