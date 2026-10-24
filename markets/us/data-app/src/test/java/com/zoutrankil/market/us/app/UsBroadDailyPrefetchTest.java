package com.zoutrankil.market.us.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.zoutrankil.data.marketdata.FrozenHttpSource;
import com.zoutrankil.data.marketdata.PersistentMarketApiBudget;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

class UsBroadDailyPrefetchTest {
    @TempDir Path root;
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1),
            THROUGH = LocalDate.of(2026, 1, 5);

    @Test
    void fetchesOnlyPlannedSourcesWhileAnotherConnectionCanAdvanceAnExistingPublication()
            throws Exception {
        try (var db = database()) {
            insert(db, "X", "PLANNED", 0);
            insert(db, "Z", "PLANNED", 10);
            insert(db, "P", "PUBLISHING", 0);
            insert(db, "H", "SCOPE_REVIEW_REQUIRED", 0);
            insert(db, "V", "VERIFIED", 0);
            var response = response(false);
            assertEquals(
                    UsBroadDailyPrefetch.Step.FETCHED,
                    UsBroadDailyPrefetch.step(
                            db,
                            16,
                            FROM,
                            THROUGH,
                            task -> {
                                assertEquals("X", task.symbol());
                                assertTrue(db.getAutoCommit());
                                // 网络进行时没有 SQLite 写事务；原发布可独立提交，不会被预取阻塞。
                                try (var concurrent = DriverManager.getConnection(url());
                                        var s = concurrent.createStatement()) {
                                    assertEquals(
                                            1,
                                            s.executeUpdate(
                                                    "UPDATE broad_tasks SET proof='publication-progress' WHERE symbol='P' AND phase='PUBLISHING'"));
                                }
                                return response;
                            },
                            () -> false));
            assertEquals("FETCHED", phase(db, "X"));
            assertEquals("PLANNED", phase(db, "Z"));
            assertEquals("PUBLISHING", phase(db, "P"));
            assertEquals("SCOPE_REVIEW_REQUIRED", phase(db, "H"));
            assertEquals("VERIFIED", phase(db, "V"));
            try (var s = db.createStatement();
                    var r =
                            s.executeQuery(
                                    "SELECT raw,sha,proof FROM broad_tasks WHERE symbol='X'")) {
                assertTrue(r.next());
                assertEquals(response.path().toString(), r.getString(1));
                assertEquals(response.sha256(), r.getString(2));
                assertEquals(response.sourceUrl(), r.getString(3));
            }
        }
    }

    @Test
    void limitsDiskBufferWaitsForAFullGroupAndKeepsTheTerminalTail() throws Exception {
        try (var db = database()) {
            insert(db, "X", "PLANNED", 0);
            for (int i = 0; i < 16; i++) insert(db, "C" + i, "FETCHED", 10);
            assertEquals(
                    UsBroadDailyPrefetch.Step.BUFFER_FULL,
                    UsBroadDailyPrefetch.step(
                            db,
                            16,
                            FROM,
                            THROUGH,
                            t -> {
                                fail("缓存已满时不能再请求来源");
                                return null;
                            },
                            () -> false));
            assertTrue(UsBroadDailyPrefetch.groupReady(db, 12, false));
            try (var s = db.createStatement()) {
                s.executeUpdate("DELETE FROM broad_tasks WHERE symbol LIKE 'C%' AND symbol!='C0'");
            }
            assertFalse(UsBroadDailyPrefetch.groupReady(db, 12, false));
            assertTrue(UsBroadDailyPrefetch.groupReady(db, 12, true));
            try (var s = db.createStatement()) {
                s.executeUpdate("DELETE FROM broad_tasks WHERE phase='FETCHED'");
            }
            assertFalse(UsBroadDailyPrefetch.groupReady(db, 12, true));
            assertEquals(
                    UsBroadDailyPrefetch.Step.STOPPED,
                    UsBroadDailyPrefetch.step(
                            db,
                            16,
                            FROM,
                            THROUGH,
                            t -> {
                                fail("停止后不能请求来源");
                                return null;
                            },
                            () -> true));
            assertEquals("PLANNED", phase(db, "X"));
        }
        for (String invalid : List.of("15", "501")) {
            var env = new StandardEnvironment();
            env.getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "test",
                                    Map.of(
                                            "app.market-data.broad-prefetch-buffer-symbols",
                                            invalid)));
            assertThrows(IllegalArgumentException.class, () -> UsBroadDailyPrefetch.capacity(env));
        }
    }

    @Test
    void preservesRejectedRawAndNeverOverwritesChangedScopeOrTurnsCancellationIntoASourceFailure()
            throws Exception {
        try (var db = database()) {
            insert(db, "X", "PLANNED", 0);
            var rejected = response(true);
            assertEquals(
                    UsBroadDailyPrefetch.Step.FAILED_SOURCE,
                    UsBroadDailyPrefetch.step(db, 16, FROM, THROUGH, t -> rejected, () -> false));
            try (var s = db.createStatement();
                    var r =
                            s.executeQuery(
                                    "SELECT phase,raw,sha FROM broad_tasks WHERE symbol='X'")) {
                assertTrue(r.next());
                assertEquals("FAILED_SOURCE", r.getString(1));
                assertEquals(rejected.path().toString(), r.getString(2));
                assertEquals(rejected.sha256(), r.getString(3));
            }
            try (var s = db.createStatement();
                    var r = s.executeQuery("SELECT count(*) FROM broad_source_retries")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
            try (var s = db.createStatement()) {
                s.executeUpdate(
                        "UPDATE broad_tasks SET phase='PLANNED',raw=NULL,sha=NULL,observed=NULL WHERE symbol='X'");
            }
            var stopped = new AtomicBoolean();
            assertEquals(
                    UsBroadDailyPrefetch.Step.STOPPED,
                    UsBroadDailyPrefetch.step(
                            db,
                            16,
                            FROM,
                            THROUGH,
                            t -> {
                                stopped.set(true);
                                throw new IOException("cancelled");
                            },
                            stopped::get));
            assertEquals("PLANNED", phase(db, "X"));
            var good = response(false);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            UsBroadDailyPrefetch.step(
                                    db,
                                    16,
                                    FROM,
                                    THROUGH,
                                    t -> {
                                        try (var other = DriverManager.getConnection(url());
                                                var s = other.createStatement()) {
                                            s.executeUpdate(
                                                    "UPDATE broad_tasks SET phase='SCOPE_REVIEW_REQUIRED' WHERE symbol='X'");
                                        }
                                        return good;
                                    },
                                    () -> false));
            assertEquals("SCOPE_REVIEW_REQUIRED", phase(db, "X"));
        }
    }

    @Test
    void quotaWaitAndUnexpectedInterruptLeaveTheOriginalTaskResumable() throws Exception {
        try (var db = database()) {
            insert(db, "X", "PLANNED", 0);
            assertThrows(
                    PersistentMarketApiBudget.Exhausted.class,
                    () ->
                            UsBroadDailyPrefetch.step(
                                    db,
                                    16,
                                    FROM,
                                    THROUGH,
                                    t -> {
                                        throw new PersistentMarketApiBudget.Exhausted(
                                                "yahoo", "hour", Instant.now().plusSeconds(3600));
                                    },
                                    () -> false));
            assertEquals("PLANNED", phase(db, "X"));
            assertThrows(
                    InterruptedException.class,
                    () ->
                            UsBroadDailyPrefetch.step(
                                    db,
                                    16,
                                    FROM,
                                    THROUGH,
                                    t -> {
                                        throw new InterruptedException();
                                    },
                                    () -> false));
            assertEquals("PLANNED", phase(db, "X"));
            db.setAutoCommit(false);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            UsBroadDailyPrefetch.step(
                                    db,
                                    16,
                                    FROM,
                                    THROUGH,
                                    t -> {
                                        fail("队列事务内不能联网");
                                        return null;
                                    },
                                    () -> false));
            db.rollback();
            db.setAutoCommit(true);
        }
    }

    @Test
    void closesAWaitingWorkerAndReportsUnexpectedWorkerFailureWithoutCompletingTheSourceScope()
            throws Exception {
        var env = new StandardEnvironment();
        env.getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "test",
                                Map.of(
                                        "app.market-data.history-start",
                                        FROM.toString(),
                                        "app.market-data.broad-prefetch-buffer-symbols",
                                        "16")));
        try (var db = database()) {
            for (int i = 0; i < 16; i++) insert(db, "C" + i, "FETCHED", 10);
            var worker = new UsBroadDailyPrefetch(env, root, THROUGH);
            assertTimeoutPreemptively(Duration.ofSeconds(5), worker::close);
            assertTrue(worker.finished());
            worker.check();
            try (var s = db.createStatement()) {
                s.executeUpdate("DELETE FROM broad_tasks");
                s.execute("DROP TABLE broad_tasks");
            }
            try (var broken = new UsBroadDailyPrefetch(env, root, THROUGH)) {
                assertTimeoutPreemptively(
                        Duration.ofSeconds(5),
                        () -> {
                            while (!broken.finished()) Thread.sleep(10);
                        });
                assertThrows(IllegalStateException.class, broken::check);
            }
        }
    }

    private String url() {
        return "jdbc:sqlite:" + root.resolve("broad-queue.sqlite3");
    }

    @Test
    void deferredWorkerDoesNotOpenBudgetUntilReadyAndCannotRestartAfterClose() throws Exception {
        var env = new StandardEnvironment();
        env.getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "test",
                                Map.of(
                                        "app.market-data.history-start",
                                        FROM.toString(),
                                        "app.market-data.broad-prefetch-buffer-symbols",
                                        "16")));
        try (var db = database()) {
            for (int i = 0; i < 16; i++) insert(db, "C" + i, "FETCHED", 10);
            var cancelled = new UsBroadDailyPrefetch(env, root, THROUGH, false);
            assertFalse(cancelled.started());
            assertFalse(Files.exists(root.resolve("api-budget.sqlite3")));
            cancelled.close();
            assertTrue(cancelled.finished());
            assertThrows(IllegalStateException.class, cancelled::start);
            assertFalse(Files.exists(root.resolve("api-budget.sqlite3")));
            try (var worker = new UsBroadDailyPrefetch(env, root, THROUGH, false)) {
                worker.start();
                worker.start();
                assertTrue(worker.started());
                assertTimeoutPreemptively(
                        Duration.ofSeconds(5),
                        () -> {
                            while (!Files.exists(root.resolve("api-budget.sqlite3")))
                                Thread.sleep(10);
                        });
                worker.check();
            }
        }
    }

    private Connection database() throws Exception {
        var db = DriverManager.getConnection(url());
        try (var s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute(
                    "CREATE TABLE broad_tasks(id TEXT PRIMARY KEY,kind TEXT,symbol TEXT,yahoo TEXT,phase TEXT,raw TEXT,sha TEXT,observed TEXT,proof TEXT,updated_at TEXT,priority INT,bundle_id TEXT)");
        }
        UsBroadSourceRetry.initialize(db);
        return db;
    }

    private static void insert(Connection db, String symbol, String phase, int priority)
            throws Exception {
        String id =
                VerifiedMarketDataStore.sha(
                        ("v1|DAILY|" + symbol + "|" + THROUGH + "|")
                                .getBytes(StandardCharsets.UTF_8));
        try (var s =
                db.prepareStatement(
                        "INSERT INTO broad_tasks(id,kind,symbol,yahoo,phase,priority) VALUES(?,'DAILY',?,?,?,?)")) {
            s.setString(1, id);
            s.setString(2, symbol);
            s.setString(3, symbol);
            s.setString(4, phase);
            s.setInt(5, priority);
            s.executeUpdate();
        }
    }

    private static String phase(Connection db, String symbol) throws Exception {
        try (var s = db.prepareStatement("SELECT phase FROM broad_tasks WHERE symbol=?")) {
            s.setString(1, symbol);
            try (var r = s.executeQuery()) {
                assertTrue(r.next());
                return r.getString(1);
            }
        }
    }

    private FrozenHttpSource.Response response(boolean missing) throws Exception {
        String json =
                """
                {"chart":{"error":null,"result":[{"meta":{"symbol":"X","currency":"USD","exchangeTimezoneName":"America/New_York","instrumentType":"EQUITY"},"timestamp":[1767378600],"indicators":{"quote":[{"open":[10],"high":[11],"low":[9],"close":[10],"volume":[100]}],"adjclose":[{"adjclose":[10]}]}}]}}
                """;
        if (missing) json = json.replace("\"close\":[10]", "\"close\":[null]");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        Path path = root.resolve(UUID.randomUUID() + ".response");
        Files.write(path, body);
        return new FrozenHttpSource.Response(
                path,
                VerifiedMarketDataStore.sha(body),
                Instant.EPOCH,
                "https://query1.finance.yahoo.com/v8/finance/chart/X",
                body.length);
    }
}
