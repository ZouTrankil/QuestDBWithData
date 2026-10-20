package com.zoutrankil.market.us.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.market.us.marketdata.UsYahooDailyBundle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class UsDailyGroupCheckpointTest {
    @TempDir Path root;

    @Test
    void oneChangedMemberRollsBackTheWholeCheckpointAndAValidGroupAdvancesOnce() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("queue.sqlite3"));
                var sql = db.createStatement()) {
            sql.execute(
                    "CREATE TABLE broad_bundles(id TEXT PRIMARY KEY,raw TEXT,sha TEXT,phase TEXT,proof TEXT,updated_at TEXT)");
            sql.execute(
                    "CREATE TABLE broad_tasks(id TEXT PRIMARY KEY,phase TEXT,proof TEXT,updated_at TEXT,bundle_id TEXT,sha TEXT)");
            sql.execute(
                    "INSERT INTO broad_bundles VALUES('group','raw','sha','PUBLISHING',NULL,NULL)");
            sql.execute(
                    "INSERT INTO broad_tasks VALUES('one','PUBLISHING',NULL,NULL,'group','s1'),('two','FETCHED',NULL,NULL,'group','s2')");
            var members =
                    List.of(
                            new UsYahooDailyBundle.Member(
                                    "one", "A", "A", "url", "s1", Instant.EPOCH, "0000.response"),
                            new UsYahooDailyBundle.Member(
                                    "two", "B", "B", "url", "s2", Instant.EPOCH, "0001.response"));
            var parsed =
                    new UsYahooDailyBundle.Parsed(
                            "group",
                            LocalDate.of(2026, 1, 1),
                            LocalDate.of(2026, 1, 2),
                            members,
                            List.of(Map.of("row", 1)));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            UsBroadDailyCollector.finishBundle(
                                    db, parsed, Map.of("run", "original")));
            try (var r = sql.executeQuery("SELECT phase FROM broad_bundles")) {
                assertTrue(r.next());
                assertEquals("PUBLISHING", r.getString(1));
            }
            try (var r = sql.executeQuery("SELECT phase FROM broad_tasks WHERE id='one'")) {
                assertTrue(r.next());
                assertEquals("PUBLISHING", r.getString(1));
            }
            sql.execute("UPDATE broad_tasks SET phase='PUBLISHING' WHERE id='two'");
            UsBroadDailyCollector.finishBundle(db, parsed, Map.of("run", "original"));
            try (var r =
                    sql.executeQuery("SELECT count(*) FROM broad_tasks WHERE phase='VERIFIED'")) {
                assertTrue(r.next());
                assertEquals(2, r.getInt(1));
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> UsBroadDailyCollector.finishBundle(db, parsed, Map.of("run", "another")));
        }
    }

    @Test
    void allMembersAndTheSourceGroupMustEnterPublishingInTheSameTransaction() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("begin.sqlite3"));
                var sql = db.createStatement()) {
            sql.execute(
                    "CREATE TABLE broad_bundles(id TEXT PRIMARY KEY,raw TEXT,sha TEXT,phase TEXT,proof TEXT,updated_at TEXT)");
            sql.execute(
                    "CREATE TABLE broad_tasks(id TEXT PRIMARY KEY,phase TEXT,proof TEXT,updated_at TEXT,bundle_id TEXT,sha TEXT)");
            sql.execute(
                    "INSERT INTO broad_tasks VALUES('one','FETCHED',NULL,NULL,NULL,'s1'),('two','PLANNED',NULL,NULL,NULL,'s2')");
            var members =
                    List.of(
                            new UsYahooDailyBundle.Member(
                                    "one", "A", "A", "url", "s1", Instant.EPOCH, "0000.response"),
                            new UsYahooDailyBundle.Member(
                                    "two", "B", "B", "url", "s2", Instant.EPOCH, "0001.response"));
            var parsed =
                    new UsYahooDailyBundle.Parsed(
                            "group",
                            LocalDate.of(2026, 1, 1),
                            LocalDate.of(2026, 1, 2),
                            members,
                            List.of(Map.of("row", 1)));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            UsBroadDailyCollector.beginBundle(
                                    db, parsed, root.resolve("source.zip"), "sha"));
            try (var r = sql.executeQuery("SELECT count(*) FROM broad_bundles")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
            try (var r =
                    sql.executeQuery("SELECT phase,bundle_id FROM broad_tasks WHERE id='one'")) {
                assertTrue(r.next());
                assertEquals("FETCHED", r.getString(1));
                assertNull(r.getString(2));
            }
            sql.execute("UPDATE broad_tasks SET phase='FETCHED' WHERE id='two'");
            UsBroadDailyCollector.beginBundle(db, parsed, root.resolve("source.zip"), "sha");
            try (var r =
                    sql.executeQuery(
                            "SELECT count(*) FROM broad_tasks WHERE phase='PUBLISHING' AND bundle_id='group'")) {
                assertTrue(r.next());
                assertEquals(2, r.getInt(1));
            }
        }
    }
}
