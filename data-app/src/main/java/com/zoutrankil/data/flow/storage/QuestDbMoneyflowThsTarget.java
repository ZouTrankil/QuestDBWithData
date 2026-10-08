package com.zoutrankil.data.flow.storage;

import com.zoutrankil.data.domain.MoneyflowThsDataset;
import com.zoutrankil.data.flow.port.MoneyflowThsTarget;
import com.zoutrankil.data.flow.port.MoneyflowThsWriteSession;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;

/** QuestDB operations for the configured target; no run or recovery state is retained here. */
public final class QuestDbMoneyflowThsTarget implements MoneyflowThsTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbMoneyflowThsTarget(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc=Objects.requireNonNull(jdbc); this.questdb=Objects.requireNonNull(questdb);
        MoneyflowThsDataset.requireIsolatedTable(table); this.table=table;
    }
    @Override public String tableName() { return table; }
    @Override public String targetId() {
        MoneyflowThsDataset.requireIsolatedTable(table);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact isolated D025 target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }
    @Override public MoneyflowThsWriteSession newWriter(String frozenTargetId) {
        return new MoneyflowThsWritePort(table,frozenTargetId,jdbc,questdb);
    }
}
