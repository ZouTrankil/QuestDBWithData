package com.zoutrankil.market.btc.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

class CryptoPlanningTest {
    private static Connection queue() throws Exception {
        var db = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var s = db.createStatement()) {
            s.execute(
                    "CREATE TABLE crypto_tasks(id TEXT PRIMARY KEY,source TEXT,symbol TEXT,instrument TEXT,from_date TEXT,through_date TEXT,phase TEXT,raw TEXT,sha TEXT,observed TEXT,proof TEXT,updated_at TEXT)");
        }
        return db;
    }

    private static List<List<String>> snapshot(Connection db) throws Exception {
        var result = new ArrayList<List<String>>();
        try (var s = db.createStatement();
                var rows =
                        s.executeQuery(
                                "SELECT id,source,symbol,instrument,from_date,through_date,phase,coalesce(proof,''),updated_at FROM crypto_tasks ORDER BY id")) {
            while (rows.next()) {
                var row = new ArrayList<String>();
                for (int i = 1; i <= 9; i++) row.add(rows.getString(i));
                result.add(row);
            }
        }
        return result;
    }

    @Test
    void extendingPartialMonthOnlyAddsEightUnseenDayTasksAndPreservesAllOriginalValues()
            throws Exception {
        try (var db = queue()) {
            CryptoHistoryCollector.plan(
                    db,
                    LocalDate.parse("2026-10-01"),
                    LocalDate.parse("2026-10-18"),
                    YearMonth.of(2026, 10));
            try (var s = db.createStatement()) {
                s.executeUpdate("UPDATE crypto_tasks SET phase='VERIFIED',proof='original-proof'");
            }
            var original = snapshot(db);
            assertEquals(76, original.size());
            CryptoHistoryCollector.plan(
                    db,
                    LocalDate.parse("2026-10-01"),
                    LocalDate.parse("2026-10-19"),
                    YearMonth.of(2026, 10));
            var extended = snapshot(db);
            assertTrue(extended.containsAll(original));
            assertEquals(84, extended.size());
            var added = extended.stream().filter(x -> !original.contains(x)).toList();
            assertTrue(
                    added.stream()
                            .allMatch(
                                    x ->
                                            x.get(4).equals("2026-10-19")
                                                    && x.get(5).equals("2026-10-19")));
            CryptoHistoryCollector.plan(
                    db,
                    LocalDate.parse("2026-10-01"),
                    LocalDate.parse("2026-10-19"),
                    YearMonth.of(2026, 10));
            assertEquals(extended, snapshot(db));
        }
    }

    @Test
    void monthRolloverKeepsDailyArchiveIdentityInsteadOfAddingOverlappingMonthlyArchive()
            throws Exception {
        try (var db = queue()) {
            CryptoHistoryCollector.plan(
                    db,
                    LocalDate.parse("2026-09-01"),
                    LocalDate.parse("2026-09-10"),
                    YearMonth.of(2026, 9));
            var original = snapshot(db);
            CryptoHistoryCollector.plan(
                    db,
                    LocalDate.parse("2026-09-01"),
                    LocalDate.parse("2026-09-30"),
                    YearMonth.of(2026, 10));
            var after = snapshot(db);
            assertTrue(after.containsAll(original));
            assertEquals(128, after.size());
            assertTrue(after.stream().noneMatch(x -> x.get(1).equals("BINANCE_MONTH")));
            assertEquals(
                    4,
                    after.stream()
                            .filter(
                                    x ->
                                            x.get(1).equals("BYBIT_MONTH")
                                                    && x.get(4).equals("2026-09-11")
                                                    && x.get(5).equals("2026-09-30"))
                            .count());
        }
    }

    @Test
    void interiorMissingDatesArePlannedEvenWhenLaterDatesAlreadyExist() throws Exception {
        try (var db = queue()) {
            CryptoHistoryCollector.plan(
                    db,
                    LocalDate.parse("2026-09-01"),
                    LocalDate.parse("2026-09-30"),
                    YearMonth.of(2026, 9));
            try (var s = db.createStatement()) {
                assertEquals(
                        4,
                        s.executeUpdate(
                                "DELETE FROM crypto_tasks WHERE source='BINANCE_DAY' AND from_date='2026-09-15'"));
            }
            var original = snapshot(db);
            CryptoHistoryCollector.plan(
                    db,
                    LocalDate.parse("2026-09-01"),
                    LocalDate.parse("2026-09-30"),
                    YearMonth.of(2026, 10));
            var after = snapshot(db);
            assertTrue(after.containsAll(original));
            assertEquals(original.size() + 4, after.size());
            assertTrue(
                    after.stream()
                            .filter(x -> !original.contains(x))
                            .allMatch(
                                    x ->
                                            x.get(1).equals("BINANCE_DAY")
                                                    && x.get(4).equals("2026-09-15")
                                                    && x.get(5).equals("2026-09-15")));
        }
    }

    @Test
    void failedFetchedAndPublishingRangesRemainReservedAndUnchanged() throws Exception {
        try (var db = queue()) {
            CryptoHistoryCollector.plan(
                    db,
                    LocalDate.parse("2026-09-01"),
                    LocalDate.parse("2026-09-30"),
                    YearMonth.of(2026, 10));
            try (var s = db.createStatement()) {
                s.executeUpdate(
                        "UPDATE crypto_tasks SET phase=CASE WHEN symbol='BTCUSDT' THEN 'PUBLISHING' WHEN instrument='SPOT' THEN 'FAILED_SOURCE' ELSE 'FETCHED' END,proof='retained-intent'");
            }
            var original = snapshot(db);
            assertEquals(8, original.size());
            CryptoHistoryCollector.plan(
                    db,
                    LocalDate.parse("2026-09-01"),
                    LocalDate.parse("2026-09-30"),
                    YearMonth.of(2026, 10));
            assertEquals(original, snapshot(db));
        }
    }
}
