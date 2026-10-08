package com.zoutrankil.data.stock.storage;
import com.zoutrankil.data.stock.port.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.domain.DailyBasicTargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.config.QuestDbProperties;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
public final class DailyQuestDbTarget implements DailyTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbProperties properties;
    public DailyQuestDbTarget(String table, JdbcTemplate jdbc, QuestDB questdb, QuestDbProperties properties) {
        this.table=table; this.jdbc=jdbc; this.questdb=questdb; this.properties=properties;
    }
    @Override public String tableName() { return table; }
    @Override public String targetId() throws Exception {
        com.zoutrankil.data.domain.policy.IsolatedTablePolicy.DAILY.require(table);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number)
                || rows.getFirst().get("directoryName") == null) {
            throw new IllegalStateException("Exact QuestDB physical identity required for daily");
        }
        String identity = properties.getHost() + ":" + properties.getPgPort() + ":"
                + properties.getQwpPort() + ":" + properties.getDatabase() + ":" + table + ":"
                + rows.getFirst().get("id") + ":" + rows.getFirst().get("directoryName");
        return "questdb-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8)));
    }
    @Override public DailyWriteSession newWriter() { return new DailyWritePort(table,jdbc,questdb); }

}
