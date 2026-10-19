package com.zoutrankil.data.flow.storage;

import com.zoutrankil.data.domain.MoneyflowDcDataset;
import com.zoutrankil.data.flow.port.MoneyflowDcTarget;
import com.zoutrankil.data.flow.port.MoneyflowDcWriteSession;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;

/** QuestDB operations for the configured target; no run or recovery state is retained here. */
public final class QuestDbMoneyflowDcTarget implements MoneyflowDcTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbMoneyflowDcTarget(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc=Objects.requireNonNull(jdbc); this.questdb=Objects.requireNonNull(questdb);
        MoneyflowDcDataset.requireIsolatedTable(table); this.table=table;
    }
    @Override public String tableName() { return table; }
    @Override public String targetId(){var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String directory))throw new IllegalStateException("Exact D026 isolated target identity required");return StaticTargetIdentity.identify(jdbc,table,id.longValue(),directory);}
    @Override public MoneyflowDcWriteSession newWriter(String frozenTargetId) {
        return new MoneyflowDcWritePort(table,frozenTargetId,jdbc,questdb);
    }
}
