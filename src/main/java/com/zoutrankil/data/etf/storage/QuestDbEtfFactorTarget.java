package com.zoutrankil.data.etf.storage;

import com.zoutrankil.data.domain.EtfFactor;
import com.zoutrankil.data.domain.EtfFactorKey;
import com.zoutrankil.data.domain.EtfFactorDataset;
import com.zoutrankil.data.etf.domain.EtfTargetRange;
import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.etf.port.EtfWriteSession;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import io.questdb.client.QuestDB;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Configured physical target; each execution obtains an independent write session. */
public final class QuestDbEtfFactorTarget implements EtfTarget<EtfFactor,EtfFactorKey> {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbEtfFactorTarget(String table,JdbcTemplate jdbc,QuestDB questdb) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.questdb=Objects.requireNonNull(questdb);
        EtfFactorDataset.requireExecutionTable(table);
        this.table=table;
    }

    @Override public String tableName() { return table; }

    @Override public String targetId() {
        EtfFactorDataset.requireExecutionTable(table);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact isolated etf_factor QuestDB table required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }

    @Override public EtfTargetRange range() {
        String sql = "SELECT cast(min(trade_date) AS long) AS min_micros, cast(max(trade_date) AS long) AS max_micros FROM \"" + table + "\"";
        return jdbc.query(sql, rs -> {
            if (!rs.next()) throw new IllegalStateException("QuestDB did not return the etf_factor date range");
            Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
            if (min == null && max == null) return checkedRange(null, null);
            if (!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                throw new IllegalStateException("QuestDB etf_factor timestamp range has invalid types");
            var from = com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(minValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            var to = com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(maxValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            return checkedRange(from, to);
        });
    }

    private static EtfTargetRange checkedRange(LocalDate min,LocalDate max) {
        if ((min == null) != (max == null) || min != null && min.isAfter(max))
            throw new IllegalStateException("Invalid etf_factor physical date range");
        return new EtfTargetRange(min,max);
    }

    @Override public EtfWriteSession<EtfFactor,EtfFactorKey> newWriter(String frozenTargetId) {
        return new EtfFactorWritePort(table,frozenTargetId,jdbc,questdb);
    }
}
