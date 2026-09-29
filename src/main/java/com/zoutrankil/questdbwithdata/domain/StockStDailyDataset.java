package com.zoutrankil.questdbwithdata.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** D012's explicit derived source-to-physical contract for the audited external stk_st_daily table. */
public final class StockStDailyDataset {
    private StockStDailyDataset() {}
    private static final TemporalContract CALENDAR_DAY = new TemporalContract(TemporalKind.BUSINESS_DATE,
            "BASIC", "calendar", "DAY", "SSE open trade date stored as UTC-midnight carrier");
    public static final DatasetDefinition DEFINITION = definition("stk_st_daily");

    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("stk_st_daily", 1, "tushare.namechange", "stk_st_daily_owner", table,
                ObjectKind.TABLE, List.of(
                new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                        "Tushare mainland instrument code including exchange suffix", null),
                new Column("derived:namechange.name,start_date,end_date", "is_st", "is_st", StorageType.INT, false,
                        "Derived 1 when the effective name contains ST; inactive intervals produce no row", null),
                new Column("exchange_calendar.cal_date", "timestamp", "timestamp", StorageType.TIMESTAMP, false,
                        "Inclusive effective name interval expanded only to SSE open dates", CALENDAR_DAY)),
                List.of("ts_code", "timestamp"), List.of("ts_code", "timestamp"), "timestamp", Partition.YEAR,
                true, Set.of(Capability.READ, Capability.WRITE), List.of("exchange_calendar", "stock_detail_info"),
                "The audited external object is YEAR-partitioned WAL with DEDUP UPSERT KEYS(ts_code,timestamp). "
                        + "No formal-table DDL is applied; ST periods are materialized to positive daily rows. "
                        + "A bounded source revision replaces the authoritative interval through a durable isolated-stage "
                        + "publication, preserving all rows outside the interval and removing revoked keys without "
                        + "synthesizing is_st=0 rows.");
    }

    /** DDL for an explicitly owned isolated acceptance target only. */
    public static String createIsolatedTableSql(String table) {
        DatasetDefinition.identifier(table);
        return "CREATE TABLE " + table + " (ts_code SYMBOL, is_st INT, timestamp TIMESTAMP) "
                + "TIMESTAMP(timestamp) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code,timestamp)";
    }
}
