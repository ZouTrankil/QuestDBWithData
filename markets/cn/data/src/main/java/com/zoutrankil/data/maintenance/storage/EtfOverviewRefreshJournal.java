package com.zoutrankil.data.maintenance.storage;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.fund.EtfOverviewScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 新版刷新先落意图；未决期间阻断同一物理输出，不随配置改绑或自动重发。 */
public final class EtfOverviewRefreshJournal {
    public record Operation(
            String id,
            String checkpoint,
            EtfOverviewScope scope,
            String left,
            String right,
            String outputBefore,
            String definitionJson,
            String definitionSql,
            String state) {}

    private final Path file;

    public EtfOverviewRefreshJournal(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    private Connection writable() throws Exception {
        Files.createDirectories(file.getParent());
        var db = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (var s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA synchronous=FULL");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS native_etf_refresh_ops(id TEXT PRIMARY KEY,checkpoint TEXT NOT NULL,scope_json TEXT NOT NULL,left_version TEXT NOT NULL,right_version TEXT NOT NULL,output_before TEXT NOT NULL,definition_json TEXT NOT NULL,definition_sql TEXT NOT NULL,state TEXT NOT NULL,created_at TEXT NOT NULL,verified_output TEXT)");
            s.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS native_etf_refresh_active ON native_etf_refresh_ops(checkpoint) WHERE state='IN_DOUBT'");
        } catch (Exception failure) {
            db.close();
            throw failure;
        }
        return db;
    }

    /** 读路径不建账本或迁表；未知来源 JSON 仍由类型及命名空间校验拒绝。 */
    public Optional<Operation> pending(String checkpoint) {
        return read("checkpoint=? AND state='IN_DOUBT'", checkpoint);
    }

    public Operation get(String id) {
        return read("id=?", id).orElseThrow(() -> new IllegalArgumentException("原 ETF 刷新意图不存在"));
    }

    public List<Operation> pendingOperations() {
        if (!Files.isRegularFile(file)) return List.of();
        try (var db =
                        DriverManager.getConnection(
                                "jdbc:sqlite:" + file.toUri().toASCIIString() + "?mode=ro");
                var s = db.createStatement()) {
            s.execute("PRAGMA query_only=ON");
            s.execute("PRAGMA busy_timeout=5000");
            try (var r =
                    s.executeQuery(
                            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='native_etf_refresh_ops'")) {
                if (!r.next() || r.getInt(1) == 0) return List.of();
            }
            var ids = new ArrayList<String>();
            try (var r =
                    s.executeQuery(
                            "SELECT id FROM native_etf_refresh_ops WHERE state='IN_DOUBT' ORDER BY created_at LIMIT 201")) {
                while (r.next()) ids.add(r.getString(1));
            }
            if (ids.size() > 200) throw new IllegalStateException("ETF 未决记录超过状态查询预算");
            return ids.stream().map(this::get).toList();
        } catch (Exception failure) {
            throw new IllegalStateException("ETF 原未决记录无法读取", failure);
        }
    }

    private Optional<Operation> read(String predicate, String value) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (var db =
                        DriverManager.getConnection(
                                "jdbc:sqlite:" + file.toUri().toASCIIString() + "?mode=ro");
                var s = db.createStatement()) {
            s.execute("PRAGMA query_only=ON");
            s.execute("PRAGMA busy_timeout=5000");
            try (var r =
                    s.executeQuery(
                            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='native_etf_refresh_ops'")) {
                if (!r.next() || r.getInt(1) == 0) return Optional.empty();
            }
            try (var q =
                    db.prepareStatement(
                            "SELECT id,checkpoint,scope_json,left_version,right_version,output_before,definition_json,definition_sql,state FROM native_etf_refresh_ops WHERE "
                                    + predicate)) {
                q.setString(1, value);
                try (var r = q.executeQuery()) {
                    if (!r.next()) return Optional.empty();
                    var result =
                            new Operation(
                                    r.getString(1),
                                    r.getString(2),
                                    JobDefinitionJson.mapper()
                                            .readValue(r.getString(3), EtfOverviewScope.class),
                                    r.getString(4),
                                    r.getString(5),
                                    r.getString(6),
                                    r.getString(7),
                                    r.getString(8),
                                    r.getString(9));
                    if (r.next()) throw new IllegalStateException("ETF 活动意图不唯一");
                    return Optional.of(result);
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("ETF 刷新原账本无法读取", failure);
        }
    }

    public Operation begin(
            String checkpoint,
            EtfOverviewScope scope,
            String left,
            String right,
            String outputBefore,
            String definitionSql)
            throws Exception {
        var op =
                new Operation(
                        UUID.randomUUID().toString(),
                        checkpoint,
                        scope,
                        left,
                        right,
                        outputBefore,
                        JobDefinitionJson.mapper().writeValueAsString(scope.definition()),
                        definitionSql,
                        "IN_DOUBT");
        try (var db = writable();
                var q =
                        db.prepareStatement(
                                "INSERT INTO native_etf_refresh_ops(id,checkpoint,scope_json,left_version,right_version,output_before,definition_json,definition_sql,state,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            q.setString(1, op.id());
            q.setString(2, checkpoint);
            q.setString(3, JobDefinitionJson.mapper().writeValueAsString(scope));
            q.setString(4, left);
            q.setString(5, right);
            q.setString(6, outputBefore);
            q.setString(7, op.definitionJson());
            q.setString(8, op.definitionSql());
            q.setString(9, op.state());
            q.setString(10, Instant.now().toString());
            q.executeUpdate();
        }
        return op;
    }

    /** 只更新执行结果；原作用域、来源版本及输入 JSON 不重写。 */
    public void complete(String id, String output) throws Exception {
        try (var db = writable();
                var q =
                        db.prepareStatement(
                                "UPDATE native_etf_refresh_ops SET state='VERIFIED',verified_output=? WHERE id=? AND state='IN_DOUBT'")) {
            q.setString(1, output);
            q.setString(2, id);
            if (q.executeUpdate() != 1) throw new IllegalStateException("ETF 原意图状态已变化");
        }
    }
}
