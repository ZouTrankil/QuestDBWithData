package com.zoutrankil.data.financial.storage;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.financial.port.FinancialQueueRepository;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 本地 SQLite 保存编排进度；原同步账本仍负责正式写入状态和恢复。 */
public final class FinancialQueueStore implements FinancialQueueRepository {
    private final Path database;

    public FinancialQueueStore(Path root) {
        database = root.toAbsolutePath().normalize().resolve("queue.sqlite3");
    }

    /** 锁跨线程和进程；来源访问期间只持有文件锁，不占数据库事务。 */
    public Lease acquire() throws Exception {
        Files.createDirectories(database.getParent());
        var channel =
                FileChannel.open(
                        database.resolveSibling("queue.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        try {
            var lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("财务分片队列正在执行");
            return () -> {
                try {
                    lock.close();
                } finally {
                    channel.close();
                }
            };
        } catch (Exception failure) {
            channel.close();
            throw failure;
        }
    }

    private Connection connect() throws Exception {
        var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=FULL");
        }
        return connection;
    }

    public void initialize() throws Exception {
        try (var connection = connect();
                var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS financial_daily_queue (id TEXT PRIMARY KEY, payload TEXT NOT NULL)");
        }
    }

    public boolean exists(String key) throws Exception {
        try (var connection = connect();
                var query =
                        connection.prepareStatement(
                                "SELECT 1 FROM financial_daily_queue WHERE id=?")) {
            query.setString(1, key);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    public <T> T read(String key, Class<T> type) throws Exception {
        try (var connection = connect();
                var query =
                        connection.prepareStatement(
                                "SELECT payload FROM financial_daily_queue WHERE id=?")) {
            query.setString(1, key);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("财务队列记录缺失：" + key);
                return JobDefinitionJson.mapper().readValue(rows.getString(1), type);
            }
        }
    }

    public void write(String key, Object value) throws Exception {
        try (var connection = connect();
                var statement =
                        connection.prepareStatement(
                                "INSERT INTO financial_daily_queue(id,payload) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload")) {
            statement.setString(1, key);
            statement.setString(2, JobDefinitionJson.canonicalMapper().writeValueAsString(value));
            statement.executeUpdate();
        }
    }

    public Set<String> receipts(String prefix) throws Exception {
        var ids = new HashSet<String>();
        try (var connection = connect();
                var query =
                        connection.prepareStatement(
                                "SELECT id FROM financial_daily_queue WHERE id LIKE ? AND id LIKE '%/verified'")) {
            query.setString(1, prefix + "/%");
            try (var rows = query.executeQuery()) {
                while (rows.next()) ids.add(rows.getString(1));
            }
        }
        return Set.copyOf(ids);
    }

    /** 一个只读事务取得同一时刻的轮次、预算和片状态；不扫描历史轮次。 */
    public Snapshot snapshot(PrefixResolver resolver) throws Exception {
        if (!Files.isRegularFile(database)) return new Snapshot(null, null, Map.of());
        try (var connection =
                DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=ro")) {
            connection.setAutoCommit(false);
            String active = null, allowance = null;
            try (var statement =
                            connection.prepareStatement(
                                    "SELECT id,payload FROM financial_daily_queue WHERE id IN ('active','allowance')");
                    var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (rows.getString(1).equals("active")) active = rows.getString(2);
                    else allowance = rows.getString(2);
                }
            }
            var entries = new HashMap<String, String>();
            if (active != null) {
                try (var statement =
                        connection.prepareStatement(
                                "SELECT id,payload FROM financial_daily_queue WHERE id LIKE ?")) {
                    statement.setString(1, resolver.resolve(active) + "/%");
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) entries.put(rows.getString(1), rows.getString(2));
                    }
                }
            }
            connection.commit();
            return new Snapshot(active, allowance, Map.copyOf(entries));
        }
    }
}
