package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.stock.domain.StockExecutionTables;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.data.stock.domain.StockTargetRange;
import com.zoutrankil.data.stock.port.StockLimitTarget;
import com.zoutrankil.data.stock.port.StockDateWriteSession;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.Objects;

public final class QuestDbStockLimitTarget implements StockLimitTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbStockLimitTarget(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc = Objects.requireNonNull(jdbc); this.questdb = Objects.requireNonNull(questdb);
        StockExecutionTables.requireStockLimit(table); this.table = table;
    }
    @Override public String tableName() { return table; }
    @Override public String targetId() {
        StockExecutionTables.requireStockLimit(table);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact stk_limit QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }
    @Override public StockTargetRange range() {
        String sql = "SELECT cast(min(trade_date) AS long) AS min_micros, cast(max(trade_date) AS long) AS max_micros FROM \"" + table + "\"";
        return jdbc.query(sql, rs -> {
            if (!rs.next()) throw new IllegalStateException("QuestDB did not return stk_limit date range");
            Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
            if (min == null && max == null) return checkedRange(null, null);
            if (!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                throw new IllegalStateException("QuestDB stk_limit date range has invalid types");
            var from = com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(minValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            var to = com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(maxValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            return checkedRange(from, to);
        });
    }
    private static StockTargetRange checkedRange(LocalDate min, LocalDate max) {
        if ((min == null) != (max == null) || min != null && min.isAfter(max))
            throw new IllegalStateException("Invalid stk_limit physical range");
        return new StockTargetRange(min, max);
    }
    @Override public StockDateWriteSession<StockLimit, StockLimitKey> newWriter(String frozenTargetId) {
        return new StockLimitWritePort(table,frozenTargetId,jdbc,questdb);
    }
}
