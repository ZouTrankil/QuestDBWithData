package com.zoutrankil.data.etf.storage;

import com.zoutrankil.data.domain.EtfShare;
import com.zoutrankil.data.domain.EtfShareKey;
import com.zoutrankil.data.domain.EtfShareDataset;
import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.etf.port.EtfWriteSession;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import io.questdb.client.QuestDB;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zoutrankil.data.etf.domain.EtfTargetRange;
import java.time.LocalDate;
import com.zoutrankil.data.domain.DatasetDefinition;

/** Configured physical target; each execution obtains an independent write session. */
public final class QuestDbEtfShareTarget implements EtfTarget<EtfShare,EtfShareKey> {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbEtfShareTarget(String table,JdbcTemplate jdbc,QuestDB questdb) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.questdb=Objects.requireNonNull(questdb);
        requireIsolatedTableName(table);
        this.table=table;
    }

    @Override public String tableName() { return table; }

    @Override public String targetId() {
        requireIsolatedTableName(table);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact D016 isolated etf_share QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }

    private static void requireIsolatedTableName(String table) {
        DatasetDefinition.identifier(table);
        EtfShareDataset.requireIsolatedTable(table);
    }

    @Override public EtfTargetRange range() {
        String sql = "SELECT cast(min(timestamp) AS long) AS min_micros, cast(max(timestamp) AS long) AS max_micros FROM \"" + table + "\"";
        return jdbc.query(sql, rs -> {
            if (!rs.next()) throw new IllegalStateException("QuestDB did not return etf_share date range");
            Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
            if (min == null && max == null) return checkedRange(null, null);
            if (!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                throw new IllegalStateException("QuestDB etf_share date range has invalid types");
            var from = com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(minValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            var to = com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(maxValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            return checkedRange(from, to);
        });
    }

    private static EtfTargetRange checkedRange(LocalDate min,LocalDate max) {
        if ((min == null) != (max == null) || min != null && min.isAfter(max))
            throw new IllegalStateException("Invalid etf_share physical range");
        return new EtfTargetRange(min,max);
    }

    @Override public EtfWriteSession<EtfShare,EtfShareKey> newWriter(String frozenTargetId) {
        return new EtfShareWritePort(table,frozenTargetId,jdbc,questdb);
    }
}
