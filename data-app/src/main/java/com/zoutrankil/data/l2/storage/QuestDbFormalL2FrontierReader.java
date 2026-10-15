package com.zoutrankil.data.l2.storage;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.l2.domain.FormalL2Frontier;
import com.zoutrankil.data.l2.port.FormalL2FrontierReader;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded physical identity and settled WAL read; construction performs no IO. */
public final class QuestDbFormalL2FrontierReader implements FormalL2FrontierReader {
    private static final String TABLE = "l2_daily_features";
    private final JdbcTemplate jdbc;
    private final QuestDbProperties properties;
    public QuestDbFormalL2FrontierReader(JdbcTemplate jdbc, QuestDbProperties properties) {
        this.jdbc = jdbc; this.properties = properties;
    }
    @Override public FormalL2Frontier read() throws Exception {
        if (!QuestDbWriteChecks.walSettled(jdbc, TABLE)) throw new IllegalStateException("Formal L2 WAL is not settled");
        var tables = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", TABLE);
        var logs = jdbc.queryForList("SELECT writerTxn,sequencerTxn FROM wal_tables() WHERE name=?", TABLE);
        if (tables.size() != 1 || logs.size() != 1) throw new IllegalStateException("Exact formal L2 identity required");
        String identity = properties.getHost() + ":" + properties.getPgPort() + ":" + properties.getQwpPort() + ":"
                + properties.getDatabase() + ":" + TABLE + ":" + tables.getFirst().get("id") + ":" + tables.getFirst().get("directoryName");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        return new FormalL2Frontier(hash, ((Number) logs.getFirst().get("writerTxn")).longValue(),
                ((Number) logs.getFirst().get("sequencerTxn")).longValue());
    }
}
