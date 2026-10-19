package com.zoutrankil.data.flow.storage;

import com.zoutrankil.data.domain.MoneyflowDataset;
import com.zoutrankil.data.flow.port.MoneyflowTarget;
import com.zoutrankil.data.flow.port.MoneyflowWriteSession;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;

/** QuestDB operations for the configured target; no run or recovery state is retained here. */
public final class QuestDbMoneyflowTarget implements MoneyflowTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbMoneyflowTarget(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc=Objects.requireNonNull(jdbc); this.questdb=Objects.requireNonNull(questdb);
        MoneyflowDataset.requireExecutionTable(table); this.table=table;
    }
    @Override public String tableName() { return table; }
    @Override public String targetId(){MoneyflowDataset.requireExecutionTable(table);var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String directory))throw new IllegalStateException("Exact D024 target identity required");return StaticTargetIdentity.identify(jdbc,table,id.longValue(),directory);}
    @Override public MoneyflowWriteSession newWriter(String frozenTargetId) {
        return new MoneyflowWritePort(table,frozenTargetId,jdbc,questdb);
    }
}
