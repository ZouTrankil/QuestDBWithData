package com.zoutrankil.data.domain.stock.market;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.util.List;
import java.util.Set;

/** Frozen audited stk_suspend shape. Python's ORM says YEAR; the physical snapshot says DAY. */
public final class StockSuspendDataset {
    private StockSuspendDataset() {}

    public static final String DEFAULT_TABLE = "stk_suspend";
    public static final List<String> STORAGE_COLUMNS =
            List.of("ts_code", "is_suspended", "timestamp");
    public static final DatasetDefinition DEFINITION = definition(DEFAULT_TABLE);

    /** Builds the same contract for an explicitly selected isolated QuestDB table. */
    public static DatasetDefinition definition(String table) {
        return definition(table, CnTradeDateLayout.LEGACY_V1);
    }

    /** 逻辑键原本已是 trade_date；新版只选物理列，保持 DAY/微秒和正记录含义。 */
    public static DatasetDefinition definition(String table, CnTradeDateLayout layout) {
        String dateColumn = layout.physicalDateColumn("stk_suspend");
        DatasetDefinition.identifier(table);
        return new DatasetDefinition(
                "stk_suspend",
                layout.schemaVersion(),
                "tushare.suspend_d",
                "stk_suspend_owner",
                table,
                ObjectKind.TABLE,
                List.of(
                        new Column(
                                "ts_code",
                                "ts_code",
                                "ts_code",
                                StorageType.SYMBOL,
                                false,
                                "Tushare A-share identity including exchange suffix",
                                null),
                        new Column(
                                "derived:suspend_type=S",
                                "is_suspended",
                                "is_suspended",
                                StorageType.LONG,
                                false,
                                "LONG 1 for an S suspension record applicable on trade_date; not a full-day assertion",
                                null),
                        new Column(
                                "trade_date",
                                "trade_date",
                                dateColumn,
                                StorageType.TIMESTAMP,
                                false,
                                "Tushare calendar business date stored as a UTC-midnight carrier, not an event instant",
                                new TemporalContract(
                                        TemporalKind.BUSINESS_DATE,
                                        "BASIC",
                                        "calendar",
                                        "DAY",
                                        "suspend_d trade_date"))),
                List.of("ts_code", "trade_date"),
                List.of("ts_code", dateColumn),
                dateColumn,
                Partition.DAY,
                true,
                Set.of(Capability.READ, Capability.WRITE),
                List.of(),
                layout == CnTradeDateLayout.TRADE_DATE_V2
                        ? "交易日布局 v2 改用 trade_date 物理列，保留原逻辑键、行编码、TIMESTAMP 微秒、DAY/WAL、正停牌记录及整窗修订规则，不声明全天停牌。"
                        : "Audited physical snapshot is DAY/WAL/DEDUP(ts_code,timestamp), although the Python ORM declares YEAR. "
                                + "This contract preserves the audited physical shape; no production DDL or Flyway migration is applied. "
                                + "Use a separately created DAY/WAL/dedup isolated table for acceptance.");
    }
}
