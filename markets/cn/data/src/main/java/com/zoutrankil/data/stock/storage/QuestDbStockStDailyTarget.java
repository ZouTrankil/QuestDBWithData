package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.domain.stock.basic.StockStDaily;
import com.zoutrankil.data.domain.stock.basic.StockStDailyKey;
import com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp;
import com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.data.stock.domain.StockExecutionTables;
import com.zoutrankil.data.stock.domain.StockStDailyState.Content;
import com.zoutrankil.data.stock.domain.StockTargetRange;
import com.zoutrankil.data.stock.port.StockDateWriteSession;
import com.zoutrankil.data.stock.port.StockStDailyStagingPort;
import com.zoutrankil.data.stock.port.StockStDailyTables;
import com.zoutrankil.data.stock.port.StockStDailyTarget;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import io.questdb.client.QuestDB;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public final class QuestDbStockStDailyTarget implements StockStDailyTarget.Versioned {
    private final CnTradeDateLayout layout;
    private final String dateColumn;
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbStockStDailyTarget(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this(table, jdbc, questdb, CnTradeDateLayout.LEGACY_V1);
    }

    public QuestDbStockStDailyTarget(
            String table, JdbcTemplate jdbc, QuestDB questdb, CnTradeDateLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        dateColumn = layout.physicalDateColumn("stk_st_daily");
        this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb);
        StockExecutionTables.requireStockStDaily(table);
        this.table = table;
    }

    @Override
    public int datasetVersion() {
        return layout.schemaVersion();
    }

    @Override
    public StockStDailyTarget forVersion(int version) {
        return version == layout.schemaVersion()
                ? this
                : new QuestDbStockStDailyTarget(
                        table, jdbc, questdb, CnTradeDateLayout.fromVersion(version));
    }

    @Override
    public String tableName() {
        return table;
    }

    @Override
    public String targetId() {
        StockExecutionTables.requireStockStDaily(table);
        // 原 v1 身份字节保持不动，新版使用独立布局命名空间。
        String physical =
                StaticTargetIdentity.identify(
                        jdbc, table, 0L, "d012-logical-target-v" + layout.schemaVersion());
        return "d012-logical-v"
                + layout.schemaVersion()
                + "-"
                + physical.substring("static-v2-".length());
    }

    @Override
    public String physicalTargetId() {
        StockExecutionTables.requireStockStDaily(table);
        var identity = new StockStDailyStorage(jdbc, table, layout).preflight();
        return StockStDailyStorage.targetId(jdbc, table, identity);
    }

    @Override
    public StockTargetRange range() {
        String sql =
                "SELECT cast(min("
                        + dateColumn
                        + ") AS long) AS min_micros, cast(max("
                        + dateColumn
                        + ") AS long) AS max_micros FROM \""
                        + table
                        + "\"";
        return jdbc.query(
                sql,
                rs -> {
                    if (!rs.next())
                        throw new IllegalStateException(
                                "QuestDB did not return stk_st_daily range aggregate");
                    Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
                    if (min == null && max == null) return checkedRange(null, null);
                    if (!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                        throw new IllegalStateException(
                                "QuestDB stk_st_daily range has invalid timestamp types");
                    var from =
                            CalendarTimestamp.fromStorageEpoch(
                                            minValue.longValue(), EpochUnit.MICROS)
                                    .date();
                    var to =
                            CalendarTimestamp.fromStorageEpoch(
                                            maxValue.longValue(), EpochUnit.MICROS)
                                    .date();
                    return checkedRange(from, to);
                });
    }

    private static StockTargetRange checkedRange(LocalDate min, LocalDate max) {
        if ((min == null) != (max == null) || min != null && min.isAfter(max))
            throw new IllegalStateException("Invalid stk_st_daily physical date range");
        return new StockTargetRange(min, max);
    }

    @Override
    public StockDateWriteSession<StockStDaily, StockStDailyKey> newWriter(String physicalTargetId) {
        return new StockStDailyWritePort(table, physicalTargetId, jdbc, questdb, layout);
    }

    @Override
    public StockDateWriteSession<StockStDaily, StockStDailyKey> stageWriter(
            String stageTable, String physicalTargetId) {
        return new StockStDailyWritePort(stageTable, physicalTargetId, jdbc, questdb, layout);
    }

    @Override
    public StockStDailyTables newPublicationTables() {
        return new QuestDbStockStDailyTables(jdbc, layout);
    }

    @Override
    public StockStDailyStagingPort newStaging() {
        return new StockStDailyStaging(jdbc, questdb, layout);
    }

    @Override
    public StockStDailyTables.Table openTable() {
        return new StockStDailyStorage(jdbc, table, layout);
    }

    @Override
    public Content fingerprintWindow(List<StockStDaily> rows, LocalDate from, LocalDate to)
            throws Exception {
        return StockStDailyStaging.fingerprintWindow(rows, from, to, layout);
    }
}
