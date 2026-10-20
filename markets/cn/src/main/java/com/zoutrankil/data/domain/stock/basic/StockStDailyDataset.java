package com.zoutrankil.data.domain.stock.basic;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** D012's explicit derived source-to-physical contract for the audited external stk_st_daily table. */
public final class StockStDailyDataset {
    public static final LocalDate HISTORY_ANCHOR = LocalDate.of(2010, 1, 1);

    private StockStDailyDataset() {}

    private static final TemporalContract CALENDAR_DAY =
            new TemporalContract(
                    TemporalKind.BUSINESS_DATE,
                    "BASIC",
                    "calendar",
                    "DAY",
                    "SSE open trade date stored as UTC-midnight carrier");
    public static final DatasetDefinition DEFINITION = definition("stk_st_daily");

    public static DatasetDefinition definition(String table) {
        return definition(table, CnTradeDateLayout.LEGACY_V1);
    }

    /** v2 明确交易日逻辑键及物理列；旧请求和原始来源证据保留 timestamp 编码。 */
    public static DatasetDefinition definition(String table, CnTradeDateLayout layout) {
        String dateColumn = layout.physicalDateColumn("stk_st_daily");
        DatasetDefinition.identifier(table);
        return new DatasetDefinition(
                "stk_st_daily",
                layout.schemaVersion(),
                "tushare.namechange",
                "stk_st_daily_owner",
                table,
                ObjectKind.TABLE,
                List.of(
                        new Column(
                                "ts_code",
                                "ts_code",
                                "ts_code",
                                StorageType.SYMBOL,
                                false,
                                "Tushare mainland instrument code including exchange suffix",
                                null),
                        new Column(
                                "derived:namechange.name,start_date,end_date",
                                "is_st",
                                "is_st",
                                StorageType.INT,
                                false,
                                "Derived 1 when the effective name contains ST; inactive intervals produce no row",
                                null),
                        new Column(
                                "exchange_calendar.cal_date",
                                logicalDateColumn(layout),
                                dateColumn,
                                StorageType.TIMESTAMP,
                                false,
                                "Inclusive effective name interval expanded only to SSE open dates",
                                CALENDAR_DAY)),
                List.of("ts_code", logicalDateColumn(layout)),
                List.of("ts_code", dateColumn),
                dateColumn,
                Partition.YEAR,
                true,
                Set.of(Capability.READ, Capability.WRITE),
                List.of("exchange_calendar", "stock_detail_info"),
                layout == CnTradeDateLayout.TRADE_DATE_V2
                        ? "交易日布局 v2 使用 trade_date 业务键和指定日期列；保留 TIMESTAMP 微秒、YEAR/WAL、正 ST 行及整窗修订规则，不自动迁移或改写旧请求。"
                        : "The audited external object is YEAR-partitioned WAL with DEDUP UPSERT KEYS(ts_code,timestamp). "
                                + "No formal-table DDL is applied; ST periods are materialized to positive daily rows. "
                                + "A bounded source revision replaces the authoritative interval through a durable isolated-stage "
                                + "publication, preserving all rows outside the interval and removing revoked keys without "
                                + "synthesizing is_st=0 rows.");
    }

    public static String logicalDateColumn(CnTradeDateLayout layout) {
        return layout == CnTradeDateLayout.LEGACY_V1
                ? "timestamp"
                : layout.physicalDateColumn("stk_st_daily");
    }

    /** DDL for an explicitly owned isolated acceptance target only. */
    public static String createIsolatedTableSql(String table) {
        return createIsolatedTableSql(table, CnTradeDateLayout.LEGACY_V1);
    }

    public static String createIsolatedTableSql(String table, CnTradeDateLayout layout) {
        String dateColumn = layout.physicalDateColumn("stk_st_daily");
        DatasetDefinition.identifier(table);
        return "CREATE TABLE "
                + table
                + " (ts_code SYMBOL, is_st INT, "
                + dateColumn
                + " TIMESTAMP) "
                + "TIMESTAMP("
                + dateColumn
                + ") PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code,"
                + dateColumn
                + ")";
    }
}
