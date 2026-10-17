package com.zoutrankil.data.l2.storage;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.l2.domain.L2T0TrainingLabelsRows;
import com.zoutrankil.data.l2.port.L2T0TrainingLabelsTarget;
import com.zoutrankil.data.l2.port.L2T0TrainingLabelsWriteSession;
import io.questdb.client.QuestDB;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Physical D089 identity and fresh-session factory; construction performs no I/O. */
public final class QuestDbL2T0TrainingLabelsTarget implements L2T0TrainingLabelsTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbProperties questProperties;

    public QuestDbL2T0TrainingLabelsTarget(String table, JdbcTemplate jdbc, QuestDB questdb,
            QuestDbProperties questProperties) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb);
        this.questProperties = Objects.requireNonNull(questProperties);
        this.table = table == null ? "" : table.trim();
    }

    @Override public String tableName() { return table; }
    @Override public L2T0TrainingLabelsWriteSession newWriter() {
        return new L2T0TrainingLabelsWritePort(table, jdbc, questdb);
    }
    @Override public void createIsolatedTarget(String table) {
        L2T0TrainingLabelsRows.requireIsolatedTableName(table);
        new L2T0TrainingLabelsWritePort(table, jdbc, questdb).createIsolatedTargetIfMissing();
    }

    public String targetId() throws Exception {
        L2T0TrainingLabelsRows.requireIsolatedTableName(table);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number)
                || rows.getFirst().get("directoryName") == null)
            throw new IllegalStateException("Exact D089 isolated QuestDB table identity required");
        String identity = questProperties.getHost() + ":" + questProperties.getPgPort() + ":"
                + questProperties.getQwpPort() + ":" + questProperties.getDatabase() + ":" + table + ":"
                + rows.getFirst().get("id") + ":" + rows.getFirst().get("directoryName");
        return "questdb-" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8)));
    }
}
