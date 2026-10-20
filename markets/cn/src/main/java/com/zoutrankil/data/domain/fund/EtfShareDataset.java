package com.zoutrankil.data.domain.fund;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.domain.temporal.TemporalValues.Precision;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** D016 mapping for the externally owned etf_share table; isolated D016 objects only. */
public final class EtfShareDataset {
    public static final int MAX_ROWS_PER_DATE = 5997;
    public static final String ISOLATED_PREFIX = "java_d016_etf_share_";
    private static final TemporalContract TRADE_DATE =
            new TemporalContract(
                    TemporalKind.BUSINESS_DATE,
                    "BASIC",
                    "calendar",
                    "DAY",
                    "Tushare trade_date, stored in timestamp as a UTC-midnight calendar carrier");
    private static final TemporalContract UPDATE_TIME =
            new TemporalContract(
                    TemporalKind.INSTANT,
                    "ISO_INSTANT",
                    "UTC",
                    "MICROS",
                    "Frozen Java source observation time; Python model generates update_time at write preparation, not a Tushare field");

    private EtfShareDataset() {}

    public static final DatasetDefinition DEFINITION = definition("etf_share");

    public static DatasetDefinition definition(String table) {
        return definition(table, CnTradeDateLayout.LEGACY_V1);
    }

    /** 只改交易日物理列；update_time 的来源观察时刻和值独立保留。 */
    public static DatasetDefinition definition(String table, CnTradeDateLayout layout) {
        String dateColumn = layout.physicalDateColumn("etf_share");
        DatasetDefinition.identifier(table);
        return new DatasetDefinition(
                "etf_share",
                layout.schemaVersion(),
                "tushare.fund_share",
                "etf_share_owner",
                table,
                ObjectKind.TABLE,
                List.of(
                        new Column(
                                "ts_code",
                                "ts_code",
                                "ts_code",
                                StorageType.SYMBOL,
                                false,
                                "Complete Tushare fund identity including SH/SZ exchange or OF OTC suffix",
                                null),
                        new Column(
                                "trade_date",
                                "trade_date",
                                dateColumn,
                                StorageType.TIMESTAMP,
                                false,
                                "Provider calendar date YYYYMMDD, stored as UTC-midnight carrier rather than event instant",
                                layout == CnTradeDateLayout.LEGACY_V1
                                        ? TRADE_DATE
                                        : new TemporalContract(
                                                TRADE_DATE.kind(),
                                                TRADE_DATE.sourceFormat(),
                                                TRADE_DATE.zone(),
                                                TRADE_DATE.precision(),
                                                "来源交易日，trade_date 仅使用 UTC 午夜日期载体，不是事件时刻")),
                        new Column(
                                "fd_share",
                                "fd_share",
                                "fd_share",
                                StorageType.DOUBLE,
                                true,
                                "Tushare fund shares in ten-thousand-share units; raw nullable DOUBLE",
                                null),
                        new Column(
                                "fund_type",
                                "fund_type",
                                "fund_type",
                                StorageType.STRING,
                                true,
                                "Nullable provider fund_share field; preserve explicit nulls as source revisions",
                                null),
                        new Column(
                                "market",
                                "market",
                                "market",
                                StorageType.SYMBOL,
                                false,
                                "Provider fund_share market field cross-checked against request partition; OF suffix maps to O",
                                null),
                        new Column(
                                "derived:frozen-observation-time",
                                "update_time",
                                "update_time",
                                StorageType.TIMESTAMP,
                                false,
                                "Frozen UTC observation timestamp at microsecond precision; technical metadata, not source revision time",
                                UPDATE_TIME)),
                List.of("ts_code", "trade_date"),
                List.of("ts_code", dateColumn),
                dateColumn,
                Partition.YEAR,
                true,
                Set.of(Capability.READ, Capability.WRITE),
                List.of("exchange_calendar"),
                layout == CnTradeDateLayout.LEGACY_V1
                        ? "Retain audited YEAR/WAL/DEDUP key (ts_code,timestamp). timestamp carries trade_date at UTC midnight and TIMESTAMP microsecond precision. No formal-table Flyway DDL; acceptance DDL is D016-isolated only. fund_type and market are explicit provider fields confirmed by the bounded D016 source probe; update_time remains frozen technical observation metadata."
                        : "交易日布局 v2 保留 YEAR/WAL、TIMESTAMP 微秒、完整业务键及六列值；update_time 仍为独立冻结观察时刻，不自动迁移或接管正式表。");
    }

    public static String createIsolatedTableSql(String table) {
        return createIsolatedTableSql(table, CnTradeDateLayout.LEGACY_V1);
    }

    public static String createIsolatedTableSql(String table, CnTradeDateLayout layout) {
        String dateColumn = layout.physicalDateColumn("etf_share");
        requireIsolatedTable(table);
        var definitions =
                definition(table, layout).columns().stream()
                        .map(
                                column ->
                                        "\""
                                                + column.storageName()
                                                + "\" "
                                                + column.storageType().name())
                        .toList();
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

    public static void requireIsolatedTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_PREFIX) || table.length() <= ISOLATED_PREFIX.length())
            throw new IllegalStateException("D016 execution requires java_d016_etf_share_<suffix>");
    }

    public static Instant requireObservation(Instant observation) {
        return TemporalValues.requirePrecision(observation, Precision.MICROS);
    }
}
