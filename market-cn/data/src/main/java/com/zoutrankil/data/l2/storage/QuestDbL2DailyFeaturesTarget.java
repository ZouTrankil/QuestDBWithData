package com.zoutrankil.data.l2.storage;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.l2.domain.L2DailyFeaturesRows;
import com.zoutrankil.data.l2.port.L2DailyFeaturesTarget;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import io.questdb.client.QuestDB;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Physical D086 target metadata and per-attempt writer creation. */
public final class QuestDbL2DailyFeaturesTarget implements L2DailyFeaturesTarget {
    private final String targetTable;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbProperties questProperties;
    public QuestDbL2DailyFeaturesTarget(String table,JdbcTemplate jdbc,QuestDB questdb,QuestDbProperties properties) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.questdb=Objects.requireNonNull(questdb);
        this.questProperties=Objects.requireNonNull(properties);
        this.targetTable=table==null?"":table.trim();
    }
    @Override public String tableName() { return targetTable; }
    @Override public L2DailyFeaturesWriteSession newWriter() { return new L2DailyFeaturesWritePort(targetTable,jdbc,questdb); }
    @Override public String targetId() throws Exception {
        L2DailyFeaturesRows.requireIsolatedTableName(targetTable);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", targetTable);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number)
                || rows.getFirst().get("directoryName") == null)
            throw new IllegalStateException("Exact D086 isolated QuestDB table identity required");
        String identity = questProperties.getHost() + ":" + questProperties.getPgPort() + ":"
                + questProperties.getQwpPort() + ":" + questProperties.getDatabase() + ":" + targetTable + ":"
                + rows.getFirst().get("id") + ":" + rows.getFirst().get("directoryName");
        return "questdb-" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8)));
    }
    @Override public void createIsolatedTarget(String table) {
        L2DailyFeaturesRows.requireIsolatedTableName(table);
        new L2DailyFeaturesWritePort(table, jdbc, questdb).createIsolatedTargetIfMissing();
    }
}
