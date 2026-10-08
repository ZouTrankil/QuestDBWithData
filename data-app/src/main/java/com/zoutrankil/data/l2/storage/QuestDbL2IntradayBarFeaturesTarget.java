package com.zoutrankil.data.l2.storage;

import com.zoutrankil.data.l2.port.*;
import com.zoutrankil.data.l2.domain.L2IntradayBarFeaturesRows;
import com.zoutrankil.data.config.QuestDbProperties;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Lazy;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** D087 physical target; blank startup configuration remains valid until an operation admits it. */
public final class QuestDbL2IntradayBarFeaturesTarget implements L2IntradayBarFeaturesTarget {
    private final String targetTable;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbProperties questProperties;
    public QuestDbL2IntradayBarFeaturesTarget(String table,JdbcTemplate jdbc,@Lazy QuestDB questdb,QuestDbProperties properties){
        this.jdbc=Objects.requireNonNull(jdbc);this.questdb=Objects.requireNonNull(questdb);
        this.questProperties=Objects.requireNonNull(properties);this.targetTable=table==null?"":table.trim();
    }
    public String tableName(){return targetTable;}
    public L2IntradayBarFeaturesWriteSession newWriter(){return new L2IntradayBarFeaturesWritePort(targetTable,jdbc,questdb);}
public String targetId() throws Exception {
        L2IntradayBarFeaturesRows.requireIsolatedTableName(targetTable);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", targetTable);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number)
                || rows.getFirst().get("directoryName") == null)
            throw new IllegalStateException("Exact D087 isolated QuestDB table identity required");
        String identity = questProperties.getHost() + ":" + questProperties.getPgPort() + ":"
                + questProperties.getQwpPort() + ":" + questProperties.getDatabase() + ":" + targetTable + ":"
                + rows.getFirst().get("id") + ":" + rows.getFirst().get("directoryName");
        return "questdb-" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8)));
    }
public void createIsolatedTarget(String table) {
        L2IntradayBarFeaturesRows.requireIsolatedTableName(table);
        new L2IntradayBarFeaturesWritePort(table, jdbc, questdb).createIsolatedTargetIfMissing();
    }
}
