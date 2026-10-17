package com.zoutrankil.data.l2.storage;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.l2.domain.L2EventResponseFeaturesRows;
import com.zoutrankil.data.l2.port.L2EventResponseFeaturesTarget;
import com.zoutrankil.data.l2.port.L2EventResponseFeaturesWriteSession;
import io.questdb.client.QuestDB;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Physical D088 identity and fresh-session factory; construction performs no I/O. */
public final class QuestDbL2EventResponseFeaturesTarget implements L2EventResponseFeaturesTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbProperties questProperties;

    public QuestDbL2EventResponseFeaturesTarget(String table, JdbcTemplate jdbc, QuestDB questdb,
            QuestDbProperties questProperties) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb);
        this.questProperties = Objects.requireNonNull(questProperties);
        this.table = table == null ? "" : table.trim();
    }

    @Override public String tableName() { return table; }
    @Override public L2EventResponseFeaturesWriteSession newWriter() {
        return new L2EventResponseFeaturesWritePort(table, jdbc, questdb);
    }
    @Override public void createIsolatedTarget(String table) {
        L2EventResponseFeaturesRows.requireIsolatedTableName(table);
        new L2EventResponseFeaturesWritePort(table, jdbc, questdb).createIsolatedTargetIfMissing();
    }

    public String targetId() throws Exception {
        L2EventResponseFeaturesRows.requireIsolatedTableName(table);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number)
                || rows.getFirst().get("directoryName") == null)
            throw new IllegalStateException("Exact D088 isolated QuestDB table identity required");
        String identity = questProperties.getHost() + ":" + questProperties.getPgPort() + ":"
                + questProperties.getQwpPort() + ":" + questProperties.getDatabase() + ":" + table + ":"
                + rows.getFirst().get("id") + ":" + rows.getFirst().get("directoryName");
        return "questdb-" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8)));
    }
}
