package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.domain.policy.IsolatedTablePolicy;
import com.zoutrankil.data.index.port.IndexDailyMarketTarget;
import com.zoutrankil.data.index.port.IndexDailyMarketWriteSession;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import io.questdb.client.QuestDB;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Concrete storage assembly; construction does not query or mutate the target. */
public final class QuestDbIndexDailyMarketTarget implements IndexDailyMarketTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbIndexDailyMarketTarget(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc = Objects.requireNonNull(jdbc); this.questdb = Objects.requireNonNull(questdb);
        IsolatedTablePolicy.INDEX_DAILY_MARKET.require(table); this.table = table;
    }
    @Override public String tableName() { return table; }
    @Override public String targetId() {
        IsolatedTablePolicy.INDEX_DAILY_MARKET.require(table);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact isolated D019 QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }
    @Override public IndexDailyMarketWriteSession newWriter(String frozenTargetId) {
        return new IndexDailyMarketWritePort(table, frozenTargetId, jdbc, questdb);
    }
}
