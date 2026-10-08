package com.zoutrankil.data.margin.storage;

import com.zoutrankil.data.domain.MarginDetailDataset;
import com.zoutrankil.data.margin.port.MarginDetailTarget;
import com.zoutrankil.data.margin.port.MarginDetailWriteSession;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;

/** QuestDB operations for the configured target; no run or recovery state is retained here. */
public final class QuestDbMarginDetailTarget implements MarginDetailTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbMarginDetailTarget(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc=Objects.requireNonNull(jdbc); this.questdb=Objects.requireNonNull(questdb);
        MarginDetailDataset.requireWriteTable(table); this.table=table;
    }
    @Override public String tableName() { return table; }
    @Override public String targetId(){var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String directory))throw new IllegalStateException("Exact D029 isolated target identity required");return StaticTargetIdentity.identify(jdbc,table,id.longValue(),directory);}
    @Override public MarginDetailWriteSession newWriter(String frozenTargetId) {
        return new MarginDetailWritePort(table,frozenTargetId,jdbc,questdb);
    }
}
