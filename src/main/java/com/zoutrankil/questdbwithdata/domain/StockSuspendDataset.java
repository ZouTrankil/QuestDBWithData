package com.zoutrankil.questdbwithdata.domain;

import java.util.*;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** Frozen audited stk_suspend shape. Python's ORM says YEAR; the physical snapshot says DAY. */
public final class StockSuspendDataset {
    private StockSuspendDataset() {}
    public static final String DEFAULT_TABLE = "stk_suspend";
    public static final List<String> STORAGE_COLUMNS = List.of("ts_code", "is_suspended", "timestamp");
    public static final DatasetDefinition DEFINITION = definition(DEFAULT_TABLE);

    /** Builds the same contract for an explicitly selected isolated QuestDB table. */
    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("stk_suspend", 1, "tushare.suspend_d", "stk_suspend_owner", table,
                ObjectKind.TABLE,
                List.of(
                        new Column("ts_code", "ts_code", "ts_code", StorageType.SYMBOL, false,
                                "Tushare A-share identity including exchange suffix", null),
                        new Column("derived:suspend_type=S", "is_suspended", "is_suspended", StorageType.LONG, false,
                                "LONG 1 for an S suspension record applicable on trade_date; not a full-day assertion", null),
                        new Column("trade_date", "trade_date", "timestamp", StorageType.TIMESTAMP, false,
                                "Tushare calendar business date stored as a UTC-midnight carrier, not an event instant",
                                new TemporalContract(TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "suspend_d trade_date"))),
                List.of("ts_code", "trade_date"), List.of("ts_code", "timestamp"), "timestamp", Partition.DAY, true,
                Set.of(Capability.READ, Capability.WRITE), List.of(),
                "Audited physical snapshot is DAY/WAL/DEDUP(ts_code,timestamp), although the Python ORM declares YEAR. "
                        + "This contract preserves the audited physical shape; no production DDL or Flyway migration is applied. "
                        + "Use a separately created DAY/WAL/dedup isolated table for acceptance.");
    }
}
