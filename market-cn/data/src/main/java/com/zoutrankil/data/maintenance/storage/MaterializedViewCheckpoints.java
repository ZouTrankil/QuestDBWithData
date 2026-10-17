package com.zoutrankil.data.maintenance.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Optional;

/** 刷新证书保存在本地运行账本中，不写入 QuestDB 业务表。 */
public final class MaterializedViewCheckpoints {
    public record Checkpoint(String definitionHash,String leftVersion,String rightVersion,
                             String outputVersion,String refreshFinished) {}
    private final Path ledger;

    public MaterializedViewCheckpoints(Path ledger) { this.ledger=ledger.toAbsolutePath().normalize(); }

    /** 缺少证书时拒绝读取，且读取过程不会自动创建数据库。 */
    public Optional<Checkpoint> find(String output) {
        if(!Files.isRegularFile(ledger)) return Optional.empty();
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+ledger.toUri().toASCIIString()+"?mode=ro");
            var s=db.createStatement()) {
            s.execute("PRAGMA query_only=ON");s.execute("PRAGMA busy_timeout=5000");
            try(var r=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='native_mv_checkpoints'")) {
                if(!r.next() || r.getInt(1)==0)return Optional.empty();
            }
            try(var query=db.prepareStatement("SELECT definition_hash,left_version,right_version,output_version,refresh_finished "
                    +"FROM native_mv_checkpoints WHERE output=?")) {
                query.setString(1,output);
                try(var r=query.executeQuery()) {
                    return r.next()?Optional.of(new Checkpoint(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5))):Optional.empty();
                }
            }
        } catch(Exception failure) { throw new IllegalStateException("Cannot read local MV checkpoint",failure); }
    }

    public void save(String output,Checkpoint checkpoint) throws Exception {
        Files.createDirectories(ledger.getParent());
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+ledger);var s=db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA synchronous=FULL");
            db.setAutoCommit(false);
            s.execute("CREATE TABLE IF NOT EXISTS native_mv_checkpoints(output TEXT PRIMARY KEY,definition_hash TEXT NOT NULL,"
                    +"left_version TEXT NOT NULL,right_version TEXT NOT NULL,output_version TEXT NOT NULL,"
                    +"refresh_finished TEXT NOT NULL,verified_at TEXT NOT NULL)");
            try(var insert=db.prepareStatement("INSERT INTO native_mv_checkpoints VALUES(?,?,?,?,?,?,?) ON CONFLICT(output) "
                    +"DO UPDATE SET definition_hash=excluded.definition_hash,left_version=excluded.left_version,"
                    +"right_version=excluded.right_version,output_version=excluded.output_version,"
                    +"refresh_finished=excluded.refresh_finished,verified_at=excluded.verified_at")) {
                insert.setString(1,output);insert.setString(2,checkpoint.definitionHash());insert.setString(3,checkpoint.leftVersion());
                insert.setString(4,checkpoint.rightVersion());insert.setString(5,checkpoint.outputVersion());
                insert.setString(6,checkpoint.refreshFinished());insert.setString(7,Instant.now().toString());insert.executeUpdate();
            }
            db.commit();
        }
    }
}
