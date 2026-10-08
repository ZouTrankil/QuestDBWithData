package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.stock.domain.StockExecutionTables;

import com.zoutrankil.data.domain.StockStDaily;
import com.zoutrankil.data.domain.StockStDailyKey;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.data.stock.domain.StockTargetRange;
import com.zoutrankil.data.stock.domain.StockStDailyState.Content;
import com.zoutrankil.data.stock.port.*;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public final class QuestDbStockStDailyTarget implements StockStDailyTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    public QuestDbStockStDailyTarget(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc = Objects.requireNonNull(jdbc); this.questdb = Objects.requireNonNull(questdb);
        StockExecutionTables.requireStockStDaily(table); this.table = table;
    }
    @Override public String tableName() { return table; }
    @Override public String targetId() {
        StockExecutionTables.requireStockStDaily(table);
        String physical = StaticTargetIdentity.identify(jdbc, table, 0L, "d012-logical-target-v1");
        return "d012-logical-v1-" + physical.substring("static-v2-".length());
    }
    @Override public String physicalTargetId() {
        StockExecutionTables.requireStockStDaily(table);
        var identity = new StockStDailyStorage(jdbc, table).preflight();
        return StockStDailyStorage.targetId(jdbc, table, identity);
    }
    @Override public StockTargetRange range() {
        String sql = "SELECT cast(min(timestamp) AS long) AS min_micros, cast(max(timestamp) AS long) AS max_micros FROM \"" + table + "\"";
        return jdbc.query(sql, rs -> {
            if (!rs.next()) throw new IllegalStateException("QuestDB did not return stk_st_daily range aggregate");
            Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
            if (min == null && max == null) return checkedRange(null, null);
            if (!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                throw new IllegalStateException("QuestDB stk_st_daily range has invalid timestamp types");
            var from = com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(minValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            var to = com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(maxValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            return checkedRange(from, to);
        });
    }
    private static StockTargetRange checkedRange(LocalDate min, LocalDate max) {
        if ((min == null) != (max == null) || min != null && min.isAfter(max))
            throw new IllegalStateException("Invalid stk_st_daily physical date range");
        return new StockTargetRange(min, max);
    }
    @Override public StockDateWriteSession<StockStDaily, StockStDailyKey> newWriter(String physicalTargetId) {
        return new StockStDailyWritePort(table, physicalTargetId, jdbc, questdb);
    }
    @Override public StockDateWriteSession<StockStDaily, StockStDailyKey> stageWriter(String stageTable, String physicalTargetId) {
        return new StockStDailyWritePort(stageTable, physicalTargetId, jdbc, questdb);
    }
    @Override public StockStDailyTables newPublicationTables() { return new QuestDbStockStDailyTables(jdbc); }
    @Override public StockStDailyStagingPort newStaging() { return new StockStDailyStaging(jdbc, questdb); }
    @Override public StockStDailyTables.Table openTable() { return new StockStDailyStorage(jdbc, table); }
    @Override public Content fingerprintWindow(List<StockStDaily> rows, LocalDate from, LocalDate to) throws Exception {
        return StockStDailyStaging.fingerprintWindow(rows, from, to);
    }
}
