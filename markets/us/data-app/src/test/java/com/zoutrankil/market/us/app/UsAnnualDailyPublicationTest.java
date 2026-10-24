package com.zoutrankil.market.us.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.us.marketdata.UsMarketDatasets;
import com.zoutrankil.market.us.marketdata.UsYahooAnnualBundle;
import com.zoutrankil.market.us.marketdata.UsYahooDailyBundle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

class UsAnnualDailyPublicationTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BUNDLE = "broad-00000000-0000-0000-0000-000000000001";
    private static final LocalDate FROM = LocalDate.of(2006, 1, 1),
            THROUGH = LocalDate.of(2009, 1, 5);
    private UsYahooAnnualBundle.Parsed bundle;
    private Path raw;
    private String sha;

    @Test
    void cleanStopResumesOnlyPlannedWindowsAndReverifiesCompletedRunsBeforeFinalMemberCheckpoint()
            throws Exception {
        try (var db = database()) {
            var port = new FakePort(db);
            var publisher = new UsAnnualDailyPublication(port);
            var stop = new AtomicBoolean();
            assertFalse(
                    publisher.publishRemaining(
                            db,
                            bundle,
                            raw,
                            sha,
                            stop::get,
                            (p, d) -> {
                                if (p.equals("VERIFIED_WINDOW")) stop.set(true);
                            }));
            assertEquals(List.of("VERIFIED", "PLANNED", "PLANNED", "PLANNED"), phases(db));
            assertEquals("PUBLISHING", taskPhase(db));
            assertEquals(List.of(0), port.sent);
            assertTrue(publisher.publishRemaining(db, bundle, raw, sha, () -> false, (p, d) -> {}));
            assertEquals(List.of(0, 1, 3), port.sent);
            assertTrue(port.verified.containsAll(List.of(0, 1, 3)));
            assertEquals("VERIFIED", taskPhase(db));
            assertEquals(4, port.verified.size());
            try (var s = db.createStatement();
                    var r = s.executeQuery("SELECT proof FROM broad_bundles")) {
                assertTrue(r.next());
                var proof = JSON.readTree(r.getString(1));
                assertEquals(3, proof.path("rows").asInt());
                assertTrue(proof.path("verification").path("allWindowsVerified").asBoolean());
            }
        }
    }

    @Test
    void
            failedPublicationKeepsItsIntentAndCannotBeAutomaticallyResentEvenWhenTheFakeServerAcceptedIt()
                    throws Exception {
        try (var db = database()) {
            var port = new FakePort(db);
            port.fail = 1;
            var publisher = new UsAnnualDailyPublication(port);
            assertThrows(
                    IOException.class,
                    () ->
                            publisher.publishRemaining(
                                    db, bundle, raw, sha, () -> false, (p, d) -> {}));
            assertEquals(List.of("VERIFIED", "PUBLISHING", "PLANNED", "PLANNED"), phases(db));
            assertEquals("PUBLISHING", taskPhase(db));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            publisher.publishRemaining(
                                    db, bundle, raw, sha, () -> false, (p, d) -> {}));
            assertEquals(List.of(0, 1), port.sent);
            assertThrows(
                    IllegalStateException.class,
                    () -> publisher.reconcile(db, bundle, 1, run(0), raw, sha));
            publisher.reconcile(db, bundle, 1, run(1), raw, sha);
            assertEquals(List.of(0, 1), port.sent);
            port.fail = -1;
            assertTrue(publisher.publishRemaining(db, bundle, raw, sha, () -> false, (p, d) -> {}));
            assertEquals(List.of(0, 1, 3), port.sent);
            assertEquals("VERIFIED", taskPhase(db));
        }
    }

    @Test
    void resumedPrefixSignalsReadyBeforeTheNextSendButNeverOnChangedOldValues() throws Exception {
        try (var db = database()) {
            var port = new FakePort(db);
            var publisher = new UsAnnualDailyPublication(port);
            var stop = new AtomicBoolean();
            publisher.publishRemaining(
                    db,
                    bundle,
                    raw,
                    sha,
                    stop::get,
                    (phase, detail) -> {
                        if (phase.equals("VERIFIED_WINDOW")) stop.set(true);
                    });
            assertEquals(List.of(0), port.sent);
            var ready = new AtomicBoolean();
            port.changed = true;
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            publisher.publishRemaining(
                                    db,
                                    bundle,
                                    raw,
                                    sha,
                                    () -> false,
                                    (phase, detail) -> {
                                        if (phase.equals("VERIFIED_ANNUAL_PREFIX")) ready.set(true);
                                    }));
            assertFalse(ready.get());
            assertEquals(List.of(0), port.sent);
            port.changed = false;
            port.verified.clear();
            assertTrue(
                    publisher.publishRemaining(
                            db,
                            bundle,
                            raw,
                            sha,
                            () -> false,
                            (phase, detail) -> {
                                if (phase.equals("VERIFIED_ANNUAL_PREFIX")) {
                                    assertEquals(BUNDLE, detail);
                                    assertEquals(List.of(0), port.sent);
                                    assertEquals(List.of(0), port.verified);
                                    ready.set(true);
                                }
                            }));
            assertTrue(ready.get());
            assertEquals(List.of(0, 1, 3), port.sent);
        }
    }

    @Test
    void missingWindowsChangedMembershipAndChangedOldValuesStopBeforeAnotherSend()
            throws Exception {
        try (var db = database()) {
            var port = new FakePort(db);
            var publisher = new UsAnnualDailyPublication(port);
            var stop = new AtomicBoolean();
            publisher.publishRemaining(
                    db,
                    bundle,
                    raw,
                    sha,
                    stop::get,
                    (p, d) -> {
                        if (p.equals("VERIFIED_WINDOW")) stop.set(true);
                    });
            port.changed = true;
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            publisher.publishRemaining(
                                    db, bundle, raw, sha, () -> false, (p, d) -> {}));
            assertEquals(List.of(0), port.sent);
            port.changed = false;
            try (var s = db.createStatement()) {
                s.executeUpdate("UPDATE broad_tasks SET sha='changed'");
            }
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            publisher.publishRemaining(
                                    db, bundle, raw, sha, () -> false, (p, d) -> {}));
            assertEquals(List.of(0), port.sent);
            try (var s = db.createStatement()) {
                s.executeUpdate(
                        "UPDATE broad_tasks SET sha='"
                                + bundle.sources().getFirst().sha256()
                                + "'");
                s.executeUpdate("DELETE FROM broad_bundle_windows WHERE window_no=2");
            }
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            publisher.publishRemaining(
                                    db, bundle, raw, sha, () -> false, (p, d) -> {}));
            assertEquals(List.of(0), port.sent);
        }
    }

    @Test
    void productionReconciliationRejectsAnotherWindowOrASubsetBeforeCallingTheStore()
            throws Exception {
        try (var db = database()) {
            var port = new FakePort(db);
            port.fail = 0;
            assertThrows(
                    IOException.class,
                    () ->
                            new UsAnnualDailyPublication(port)
                                    .publishRemaining(
                                            db, bundle, raw, sha, () -> false, (p, d) -> {}));
            var w = bundle.windows().getFirst();
            Path prepared = root.resolve("prepared/" + run(0) + ".json");
            Files.createDirectories(prepared.getParent());
            var evidence =
                    new HashMap<String, Object>(
                            Map.of(
                                    "bundle",
                                    BUNDLE,
                                    "window",
                                    1,
                                    "requestedFrom",
                                    w.from().toString(),
                                    "requestedThrough",
                                    w.through().toString()));
            var document =
                    new HashMap<String, Object>(
                            Map.of(
                                    "definition",
                                    Map.of("datasetId", UsMarketDatasets.DAILY.datasetId()),
                                    "sourceSha256",
                                    sha,
                                    "raw",
                                    raw.toString(),
                                    "evidence",
                                    evidence,
                                    "rows",
                                    bundle.rows(w)));
            Files.write(prepared, JSON.writeValueAsBytes(document));
            var actual = new UsAnnualDailyPublication(root, null);
            assertThrows(
                    IllegalStateException.class,
                    () -> actual.reconcile(db, bundle, 0, run(0), raw, sha));
            evidence.put("window", 0);
            document.put("rows", List.of());
            Files.write(prepared, JSON.writeValueAsBytes(document));
            assertThrows(
                    IllegalStateException.class,
                    () -> actual.reconcile(db, bundle, 0, run(0), raw, sha));
            assertEquals("PUBLISHING", phases(db).getFirst());
        }
    }

    @Test
    void populatedProjectionCacheCannotBypassAChangedFrozenZipBeforeSending() throws Exception {
        try (var db = database()) {
            assertFalse(bundle.rows(bundle.windows().getFirst()).isEmpty());
            Files.write(raw, new byte[] {1, 2, 3});
            var port = new FakePort(db);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            new UsAnnualDailyPublication(port)
                                    .publishRemaining(
                                            db, bundle, raw, sha, () -> false, (p, d) -> {}));
            assertTrue(port.sent.isEmpty());
            assertEquals(List.of("PLANNED", "PLANNED", "PLANNED", "PLANNED"), phases(db));
            assertEquals("PUBLISHING", taskPhase(db));
        }
    }

    private static class FakePort implements UsAnnualDailyPublication.Port {
        final Connection db;
        final List<Integer> sent = new ArrayList<>(), verified = new ArrayList<>();
        final Map<String, List<Map<String, Object>>> written = new HashMap<>();
        int fail = -1;
        boolean changed;

        FakePort(Connection db) {
            this.db = db;
        }

        public UsAnnualDailyPublication.Published publish(
                UsYahooAnnualBundle.Parsed b,
                UsYahooAnnualBundle.Window w,
                List<Map<String, Object>> rows,
                Path raw,
                String sha)
                throws Exception {
            try (var s = db.createStatement();
                    var r =
                            s.executeQuery(
                                    "SELECT phase FROM broad_bundle_windows WHERE window_no="
                                            + w.number())) {
                assertTrue(r.next());
                assertEquals("PUBLISHING", r.getString(1));
            }
            assertTrue(db.getAutoCommit());
            sent.add(w.number());
            written.put(run(w.number()), rows);
            if (w.number() == fail) throw new IOException("模拟服务端接受后客户端未收到结果");
            return new UsAnnualDailyPublication.Published(
                    run(w.number()), rows.size(), Map.of("run", run(w.number())));
        }

        public Map<String, Object> verify(
                String run,
                UsYahooAnnualBundle.Parsed b,
                UsYahooAnnualBundle.Window w,
                List<Map<String, Object>> rows,
                Path raw,
                String sha)
                throws Exception {
            if (changed || !rows.equals(written.get(run)))
                throw new IllegalStateException("原字段或原运行不符");
            verified.add(w.number());
            return Map.of("run", run, "rows", rows.size(), "walSettled", true);
        }
    }

    private Connection database() throws Exception {
        var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("queue.sqlite3"));
        try (var s = db.createStatement()) {
            s.execute(
                    "CREATE TABLE broad_bundles(id TEXT PRIMARY KEY,raw TEXT,sha TEXT,phase TEXT,proof TEXT,updated_at TEXT)");
            s.execute(
                    "CREATE TABLE broad_tasks(id TEXT PRIMARY KEY,kind TEXT,symbol TEXT,yahoo TEXT,phase TEXT,sha TEXT,observed TEXT,bundle_id TEXT,proof TEXT,updated_at TEXT)");
        }
        UsAnnualDailyPublication.createTables(db);
        var source = source();
        byte[] frozen =
                UsYahooAnnualBundle.freeze(
                        BUNDLE,
                        FROM,
                        THROUGH,
                        new UsYahooAnnualBundle.Page(10000, 8388608),
                        List.of(source));
        bundle = UsYahooAnnualBundle.parse(frozen);
        raw = root.resolve("bundle.zip");
        Files.write(raw, frozen);
        sha = VerifiedMarketDataStore.sha(frozen);
        try (var s =
                db.prepareStatement(
                        "INSERT INTO broad_tasks VALUES(?,'DAILY','X','X','FETCHED',?,?,NULL,NULL,NULL)")) {
            s.setString(1, source.task());
            s.setString(2, source.sha256());
            s.setString(3, source.observedAt().toString());
            s.executeUpdate();
        }
        UsAnnualDailyPublication.begin(db, bundle, raw, sha);
        return db;
    }

    private UsYahooDailyBundle.Source source() throws Exception {
        String json =
                """
                {"chart":{"error":null,"result":[{"meta":{"symbol":"X","currency":"USD","exchangeTimezoneName":"America/New_York","instrumentType":"EQUITY"},"timestamp":[1136298600,1167834600,1231165800],"indicators":{"quote":[{"open":[10,11,12],"high":[10,11,12],"low":[10,11,12],"close":[10,11,12],"volume":[100,200,300]}],"adjclose":[{"adjclose":[10,11,12]}]}}]}}
                """;
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return new UsYahooDailyBundle.Source(
                VerifiedMarketDataStore.sha(
                        ("v1|DAILY|X|" + THROUGH + "|").getBytes(StandardCharsets.UTF_8)),
                "X",
                "X",
                "https://query1.finance.yahoo.com/v8/finance/chart/X",
                VerifiedMarketDataStore.sha(bytes),
                Instant.EPOCH,
                bytes);
    }

    private static String run(int window) {
        return "market-00000000-0000-0000-0000-" + String.format(Locale.ROOT, "%012d", window);
    }

    private static List<String> phases(Connection db) throws Exception {
        var values = new ArrayList<String>();
        try (var s = db.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT phase FROM broad_bundle_windows ORDER BY window_no")) {
            while (r.next()) values.add(r.getString(1));
        }
        return values;
    }

    private static String taskPhase(Connection db) throws Exception {
        try (var s = db.createStatement();
                var r = s.executeQuery("SELECT phase FROM broad_tasks")) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }
}
