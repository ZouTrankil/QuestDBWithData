package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.domain.policy.IsolatedTablePolicy;
import com.zoutrankil.data.index.port.IndexDailyBasicTarget;
import com.zoutrankil.data.index.port.IndexDailyBasicWriteSession;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import io.questdb.client.QuestDB;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Concrete storage assembly; construction does not query or mutate the target. */
public final class QuestDbIndexDailyBasicTarget implements IndexDailyBasicTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbIndexDailyBasicTarget(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc = Objects.requireNonNull(jdbc); this.questdb = Objects.requireNonNull(questdb);
        IsolatedTablePolicy.INDEX_DAILY_BASIC.require(table); this.table = table;
    }
    @Override public String tableName() { return table; }
    @Override public String targetId() {
        IsolatedTablePolicy.INDEX_DAILY_BASIC.require(table);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact isolated D020 QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }
    @Override public IndexDailyBasicWriteSession newWriter(String frozenTargetId) {
        return new IndexDailyBasicWritePort(table, frozenTargetId, jdbc, questdb);
    }
}
