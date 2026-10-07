package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.application.DailyCheckpoint;
import com.zoutrankil.data.stock.application.DailyBasicCoverage;
import com.zoutrankil.data.stock.application.StockLimitCoverage;
import com.zoutrankil.data.stock.application.StockStDailyCoverage;
import com.zoutrankil.data.calendar.application.ExchangeCalendarCoverage;

import com.zoutrankil.data.etf.application.EtfShareCoverage;

import com.zoutrankil.data.etf.application.EtfDailyCoverage;
import com.zoutrankil.data.etf.application.EtfAdjCoverage;
import com.zoutrankil.data.etf.application.EtfCoverageTestAccess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LedgerSchemaCompatibilityTest {
    @TempDir Path root;

    @FunctionalInterface private interface Probe { boolean inspect(Path path) throws Exception; }
    private record Admission(Probe probe, int requiredTables, boolean missingIsEmpty,
                             Class<? extends Exception> failureType, String message) {}
    private static final List<Admission> ADMISSIONS = List.of(
            new Admission(DailyBasicCoverage::hasHistorySchema, 3, false, SQLException.class, "Partial sync-run ledger schema"),
            new Admission(ExchangeCalendarCoverage::hasHistorySchema, 3, false, SQLException.class, "Partial sync-run ledger schema"),
            new Admission(DailyCheckpoint::hasHistorySchema, 4, true, IllegalStateException.class, "Partial sync-run ledger schema"),
            new Admission(EtfAdjCoverage::hasHistorySchema, 4, true, IllegalStateException.class, "Partial sync-run ledger schema"),
            new Admission(EtfDailyCoverage::hasHistorySchema, 4, true, IllegalStateException.class, "Partial sync-run ledger schema"),
            new Admission(EtfShareCoverage::hasHistorySchema, 4, true, IllegalStateException.class, "Partial sync-run ledger schema"),
            new Admission(IndexDailyMarketCoverage::hasHistorySchema, 4, true, IllegalStateException.class, "Partial D019 sync ledger schema"),
            new Admission(StockLimitCoverage::hasHistorySchema, 4, true, IllegalStateException.class, "Partial sync-run ledger schema"),
            new Admission(StockStDailyCoverage::hasHistorySchema, 4, true, IllegalStateException.class, "Partial D012 sync ledger schema"),
            new Admission(EtfCoverageTestAccess::factorHasHistorySchema, 4, false, IllegalStateException.class, "Partial D017 sync ledger schema"),
            new Admission(DcIndexCoverage::hasLedger, 4, false, IllegalStateException.class, "Partial D023 sync ledger schema"));

    @Test void missingFilesKeepEachEntrypointsExistingContract() throws Exception {
        var path = root.resolve("missing.sqlite3");
        for (var admission : ADMISSIONS) {
            if (admission.missingIsEmpty()) assertFalse(admission.probe().inspect(path));
            else assertThrows(SQLException.class, () -> admission.probe().inspect(path));
        }
        assertFalse(Files.exists(path));
    }

    @Test void unrelatedScheduleTablesDoNotCountAsHistory() throws Exception {
        var path = root.resolve("schedule.sqlite3");
        createTables(path, "schedule_jobs");
        for (var admission : ADMISSIONS) assertFalse(admission.probe().inspect(path));
    }

    @Test void partialSchemaPreservesExceptionClassesAndMessages() throws Exception {
        var path = root.resolve("partial.sqlite3");
        createTables(path, "sync_runs");
        for (var admission : ADMISSIONS) {
            var failure = assertThrowsExactly(admission.failureType(), () -> admission.probe().inspect(path));
            assertEquals(admission.message(), failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test void threeTablesRemainCompleteOnlyForTheLegacyThreeTableProbes() throws Exception {
        var path = root.resolve("three.sqlite3");
        createTables(path, "ledger_meta", "sync_runs", "sync_entries");
        for (var admission : ADMISSIONS) {
            if (admission.requiredTables() == 3) assertTrue(admission.probe().inspect(path));
            else {
                var failure = assertThrowsExactly(admission.failureType(), () -> admission.probe().inspect(path));
                assertEquals(admission.message(), failure.getMessage());
            }
        }
    }

    @Test void completeSchemaIsAcceptedWithoutChangingTheFile() throws Exception {
        var path = root.resolve("complete.sqlite3");
        createTables(path, "ledger_meta", "sync_runs", "sync_entries", "sync_events");
        var before = Files.readAllBytes(path);
        for (var admission : ADMISSIONS) assertTrue(admission.probe().inspect(path));
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    private static void createTables(Path path, String... names) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            for (String name : names) statement.execute("CREATE TABLE " + name + "(id TEXT)");
        }
    }
}
