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
public final class StockBasicQuestDbTarget implements StockBasicTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbProperties properties;
    public StockBasicQuestDbTarget(String table, JdbcTemplate jdbc, QuestDB questdb, QuestDbProperties properties) {
        this.table=table; this.jdbc=jdbc; this.questdb=questdb; this.properties=properties;
    }
    @Override public String tableName() { return table; }
    @Override public String targetId() throws Exception {
        String table=StockBasicDataset.DEFINITION.objectName();
        var objects=jdbc.queryForList("SELECT id, directoryName FROM tables() WHERE table_name = ?",table);
        if(objects.size()!=1 || !(objects.getFirst().get("id") instanceof Number)
                || objects.getFirst().get("directoryName")==null)
            throw new IllegalStateException("Exact physical QuestDB target identity required");
        String address=properties.getHost()+":"+properties.getPgPort()+":"+properties.getQwpPort()+":"+properties.getDatabase()
                +":"+table+":"+objects.getFirst().get("id")+":"+objects.getFirst().get("directoryName");
        return "questdb-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(address.getBytes(StandardCharsets.UTF_8)));
    }
    @Override public VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey> newWriter() { return new StockBasicWritePort(table,jdbc,questdb); }

    @Override public VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey> newConfiguredWriter(int maxBatchBytes, java.time.Duration timeout) {
        return new StockBasicWritePort(table,jdbc,questdb,maxBatchBytes,timeout);
    }
    @Override public void verifyConnection() {
        Integer result = jdbc.queryForObject("SELECT 1", Integer.class);
        if (result == null || result != 1) throw new IllegalStateException("QuestDB JDBC probe returned an unexpected result");
    }
}
