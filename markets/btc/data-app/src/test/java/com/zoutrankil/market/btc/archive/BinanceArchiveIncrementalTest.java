package com.zoutrankil.market.btc.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.market.btc.archive.application.BinanceArchiveIncremental;
import com.zoutrankil.market.btc.archive.application.BinanceArchiveSync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

class BinanceArchiveIncrementalTest {
    @TempDir Path root;

    private Connection queue() throws Exception {
        var db =
                DriverManager.getConnection("jdbc:sqlite:" + root.resolve("archive-queue.sqlite3"));
        try (var s = db.createStatement()) {
            s.execute(
                    "CREATE TABLE IF NOT EXISTS archive_tasks(id TEXT PRIMARY KEY,phase TEXT,run_id TEXT,reason TEXT)");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS funding_api_tails(from_date TEXT,through_date TEXT,phase TEXT)");
        }
        return db;
    }

    @Test
    void monthly404FallsBackButChecksumAndPendingPublicationDoNot() throws Exception {
        var windows =
                BinanceArchiveSync.plan(
                        List.of(BinanceArchiveDataset.SPOT_1H),
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 30));
        try (var db = queue();
                var q =
                        db.prepareStatement(
                                "INSERT INTO archive_tasks VALUES(?,'SKIPPED_SOURCE',NULL,'SourceStatusException: 来源 HTTP 404')")) {
            q.setString(1, windows.getFirst().id());
            q.executeUpdate();
            assertEquals(30, BinanceArchiveIncremental.fallbackDays(root, windows).size());
            try (var s = db.createStatement()) {
                s.executeUpdate("UPDATE archive_tasks SET reason='CHECKSUM 不符'");
                assertEquals(0, BinanceArchiveIncremental.fallbackDays(root, windows).size());
                s.executeUpdate("UPDATE archive_tasks SET reason='来源 HTTP 404',phase='PUBLISHING'");
                assertEquals(0, BinanceArchiveIncremental.fallbackDays(root, windows).size());
            }
        }
    }

    @Test
    void missedPriorMonthAndNewMonthArePlannedSeparatelyWithoutReplacingOldApiTasks()
            throws Exception {
        try (var db = queue();
                var s = db.createStatement()) {
            s.execute("INSERT INTO funding_api_tails VALUES('2026-09-01','2026-09-28','VERIFIED')");
            var windows =
                    BinanceArchiveIncremental.fundingWindows(
                            root, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 2));
            assertEquals(
                    List.of(
                            new BinanceArchiveIncremental.FundingWindow(
                                    LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30)),
                            new BinanceArchiveIncremental.FundingWindow(
                                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2))),
                    windows);
            s.execute("INSERT INTO funding_api_tails VALUES('2026-09-29','2026-09-30','FETCHED')");
            assertEquals(
                    windows,
                    BinanceArchiveIncremental.fundingWindows(
                            root, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 2)));
            s.execute(
                    "UPDATE funding_api_tails SET phase='PUBLISHING' WHERE from_date='2026-09-29'");
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            BinanceArchiveIncremental.fundingWindows(
                                    root, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 2)));
        }
    }

    @Test
    void verifiedMonthlyArchiveSuppressesApiWhileHolesInsideMonthRemainVisible() throws Exception {
        try (var db = queue();
                var s = db.createStatement()) {
            s.execute("INSERT INTO funding_api_tails VALUES('2026-09-01','2026-09-03','VERIFIED')");
            s.execute("INSERT INTO funding_api_tails VALUES('2026-09-05','2026-09-30','VERIFIED')");
            assertEquals(
                    List.of(
                            new BinanceArchiveIncremental.FundingWindow(
                                    LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4))),
                    BinanceArchiveIncremental.fundingWindows(
                            root, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));
            var month = YearMonth.of(2026, 9);
            var window =
                    new BinanceArchiveSync.Window(
                            BinanceArchiveDataset.FUNDING,
                            BinanceArchiveDataset.FUNDING.monthly(month),
                            month.atDay(1),
                            month.atEndOfMonth());
            try (var q =
                    db.prepareStatement(
                            "INSERT INTO archive_tasks VALUES(?,'VERIFIED','original',NULL)")) {
                q.setString(1, window.id());
                q.executeUpdate();
            }
            assertEquals(
                    List.of(),
                    BinanceArchiveIncremental.fundingWindows(
                            root, month.atDay(1), month.atEndOfMonth()));
        }
    }
}
