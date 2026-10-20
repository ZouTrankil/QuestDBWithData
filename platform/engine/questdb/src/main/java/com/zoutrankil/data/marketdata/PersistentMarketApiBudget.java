package com.zoutrankil.data.marketdata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 跨进程共用请求账本；先预留再联网，失败也计入请求数，不靠换进程或重试绕过配额。 */
public final class PersistentMarketApiBudget implements MarketRequestBudget, AutoCloseable {
    public record Policy(
            int perHour,
            int perDay,
            int symbolsPer31Days,
            long bytesPer31Days,
            long spacingMillis) {
        public Policy {
            if (perHour < 1
                    || perDay < 1
                    || symbolsPer31Days < 1
                    || bytesPer31Days < 1
                    || spacingMillis < 0) throw new IllegalArgumentException("明确的正数 API 预算必填");
        }
    }

    public static final class Exhausted extends Exception {
        public final Instant nextAllowed;

        public Exhausted(String provider, String reason, Instant next) {
            super(provider + "：" + reason + "；最早 " + next);
            nextAllowed = next;
        }
    }

    private final Connection connection;
    private final Map<String, Policy> policies;

    public PersistentMarketApiBudget(Path file, Map<String, Policy> policies) throws Exception {
        this.policies = Map.copyOf(policies);
        Files.createDirectories(file.toAbsolutePath().getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (var s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS market_api_requests(id TEXT PRIMARY KEY,provider TEXT NOT NULL,account TEXT NOT NULL,symbol TEXT,attempt_ms LONG NOT NULL,status INT,bytes LONG NOT NULL,reserved_bytes LONG NOT NULL,blocked_until LONG)");
            s.execute(
                    "CREATE INDEX IF NOT EXISTS market_api_budget_scope ON market_api_requests(provider,account,attempt_ms)");
        }
    }

    @Override
    public synchronized String reserve(
            String provider, String account, String symbol, int maximumBytes) throws Exception {
        var p = Objects.requireNonNull(policies.get(provider), "未登记来源预算");
        long now = System.currentTimeMillis(),
                hour = now - 3600000,
                day = now - 86400000,
                month = now - 31L * 86400000;
        try (var tx = connection.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
        }
        try {
            var recent = rows(provider, account, month);
            long latest = 0, blocked = 0, usedBytes = 0;
            int hourly = 0, daily = 0;
            long firstHour = Long.MAX_VALUE, firstDay = Long.MAX_VALUE, firstMonth = Long.MAX_VALUE;
            var symbols = new HashSet<String>();
            for (var r : recent) {
                latest = Math.max(latest, r.at);
                blocked = Math.max(blocked, r.blocked);
                firstMonth = Math.min(firstMonth, r.at);
                usedBytes = Math.addExact(usedBytes, r.bytes + r.reserved);
                if (r.at > hour) {
                    hourly++;
                    firstHour = Math.min(firstHour, r.at);
                }
                if (r.at > day) {
                    daily++;
                    firstDay = Math.min(firstDay, r.at);
                }
                if (r.symbol != null && !r.symbol.isBlank()) symbols.add(r.symbol);
            }
            if (blocked > now)
                throw new Exhausted(provider, "来源要求退避", Instant.ofEpochMilli(blocked));
            if (latest + p.spacingMillis() > now)
                throw new Exhausted(
                        provider, "请求间隔", Instant.ofEpochMilli(latest + p.spacingMillis()));
            if (hourly >= p.perHour())
                throw new Exhausted(
                        provider, "小时请求预算已用完", Instant.ofEpochMilli(firstHour + 3600001));
            if (daily >= p.perDay())
                throw new Exhausted(
                        provider, "滚动 24 小时请求预算已用完", Instant.ofEpochMilli(firstDay + 86400001));
            if (symbol != null
                    && !symbol.isBlank()
                    && !symbols.contains(symbol)
                    && symbols.size() >= p.symbolsPer31Days())
                throw new Exhausted(
                        provider,
                        "滚动 31 天标的预算已用完",
                        Instant.ofEpochMilli(firstMonth + 31L * 86400000 + 1));
            if (usedBytes + maximumBytes > p.bytesPer31Days())
                throw new Exhausted(
                        provider,
                        "响应带宽预留超过滚动 31 天预算",
                        Instant.ofEpochMilli(firstMonth + 31L * 86400000 + 1));
            String id = UUID.randomUUID().toString();
            try (var q =
                    connection.prepareStatement(
                            "INSERT INTO market_api_requests VALUES(?,?,?,?,?,NULL,0,?,NULL)")) {
                q.setString(1, id);
                q.setString(2, provider);
                q.setString(3, account);
                q.setString(4, symbol);
                q.setLong(5, now);
                q.setLong(6, maximumBytes);
                q.executeUpdate();
            }
            try (var tx = connection.createStatement()) {
                tx.execute("COMMIT");
            }
            return id;
        } catch (Exception e) {
            try (var tx = connection.createStatement()) {
                tx.execute("ROLLBACK");
            }
            throw e;
        }
    }

    private record Usage(long at, String symbol, long bytes, long reserved, long blocked) {}

    private List<Usage> rows(String provider, String account, long from) throws Exception {
        var result = new ArrayList<Usage>();
        try (var q =
                connection.prepareStatement(
                        "SELECT attempt_ms,symbol,bytes,reserved_bytes,coalesce(blocked_until,0) FROM market_api_requests WHERE provider=? AND account=? AND attempt_ms>=?")) {
            q.setString(1, provider);
            q.setString(2, account);
            q.setLong(3, from);
            try (var r = q.executeQuery()) {
                while (r.next())
                    result.add(
                            new Usage(
                                    r.getLong(1),
                                    r.getString(2),
                                    r.getLong(3),
                                    r.getLong(4),
                                    r.getLong(5)));
            }
        }
        return result;
    }

    @Override
    public synchronized void complete(String id, int status, int bytes, Instant retryAfter)
            throws Exception {
        if (status == 429 && retryAfter == null) retryAfter = Instant.now().plusSeconds(60);
        // 未取得完整响应时不能假定实际带宽为零，保留该次最大响应预留。
        try (var q =
                connection.prepareStatement(
                        "UPDATE market_api_requests SET status=?,bytes=max(bytes,?),reserved_bytes=CASE WHEN ?=0 THEN reserved_bytes ELSE 0 END,blocked_until=max(coalesce(blocked_until,0),?) WHERE id=?")) {
            q.setInt(1, status);
            q.setInt(2, bytes);
            q.setInt(3, status);
            q.setLong(4, retryAfter == null ? 0 : retryAfter.toEpochMilli());
            q.setString(5, id);
            if (q.executeUpdate() != 1) throw new IllegalArgumentException("配额预留记录缺失");
        }
    }

    public synchronized void seed(
            String id, String provider, String account, String symbol, Instant at, long bytes)
            throws Exception {
        try (var q =
                connection.prepareStatement(
                        "INSERT OR IGNORE INTO market_api_requests VALUES(?,?,?,?,?,200,?,0,NULL)")) {
            q.setString(1, id);
            q.setString(2, provider);
            q.setString(3, account);
            q.setString(4, symbol);
            q.setLong(5, at.toEpochMilli());
            q.setLong(6, bytes);
            q.executeUpdate();
        }
    }

    public synchronized List<Map<String, Object>> usage() throws Exception {
        var out = new ArrayList<Map<String, Object>>();
        try (var s = connection.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT provider,count(),sum(bytes),sum(reserved_bytes),max(attempt_ms) FROM market_api_requests GROUP BY provider")) {
            while (r.next())
                out.add(
                        Map.of(
                                "provider",
                                r.getString(1),
                                "attempts",
                                r.getLong(2),
                                "receivedBytes",
                                r.getLong(3),
                                "reservedBytes",
                                r.getLong(4),
                                "lastAttempt",
                                Instant.ofEpochMilli(r.getLong(5)).toString()));
        }
        return out;
    }

    @Override
    public void close() throws Exception {
        connection.close();
    }
}
