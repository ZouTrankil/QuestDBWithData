package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.SyncRunLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class DatasetIntervalLockTest {
    @TempDir Path root;

    private Path ledger() throws Exception {
        var path = root.resolve("control.sqlite3");
        var ledger = new SyncRunLedger(path);
        ledger.createRun(new SyncRunLedger.Run("run-a", null, "job", 1, "2026-09-29", "isolated", "{}"));
        ledger.createRun(new SyncRunLedger.Run("run-b", null, "job", 1, "2026-09-29", "isolated", "{}"));
        return path;
    }

    @Test void overlappingDatasetIntervalsConflictAcrossInstances() throws Exception {
        var path = ledger();
        var a = new DatasetIntervalLock(path);
        var b = new DatasetIntervalLock(path);
        var from = LocalDate.of(2026, 9, 1);
        var held = a.acquire("run-a", new DatasetIntervalLock.Scope("daily", from, from.plusDays(7)));
        assertNotNull(held);
        assertNull(b.acquire("run-b", new DatasetIntervalLock.Scope("daily", from.plusDays(7), from.plusDays(8))));
        var adjacent = b.acquire("run-b", new DatasetIntervalLock.Scope("daily", from.plusDays(8), from.plusDays(10)));
        assertNotNull(adjacent);
        var otherDataset = b.acquire("run-b", new DatasetIntervalLock.Scope("etf_daily", from, from.plusDays(7)));
        assertNotNull(otherDataset);
        b.releaseVerified(adjacent); b.releaseVerified(otherDataset);
        a.releaseVerified(held);
        assertNotNull(b.acquire("run-b", DatasetIntervalLock.Scope.allDates("daily")));
    }

    @Test void uncertainWriterKeepsConflictUntilExplicitReconciliation() throws Exception {
        var path = ledger();
        var a = new DatasetIntervalLock(path);
        var held = a.acquire("run-a", DatasetIntervalLock.Scope.allDates("stock_basic_snapshot"));
        a.retainInDoubt(held);
        var reopened = new DatasetIntervalLock(path);
        assertNull(reopened.acquire("run-b", DatasetIntervalLock.Scope.allDates("stock_basic_snapshot")));
        assertThrows(IllegalStateException.class, () -> reopened.releaseVerified(held));
        assertThrows(IllegalStateException.class, () -> reopened.releaseAfterReconciliation(held, false, true));
        assertThrows(IllegalStateException.class, () -> reopened.releaseAfterReconciliation(held, true, false));
        reopened.releaseAfterReconciliation(held, true, true);
        assertNotNull(reopened.acquire("run-b", DatasetIntervalLock.Scope.allDates("stock_basic_snapshot")));
    }

    @Test void concurrentAcquisitionGrantsAtMostOneLease() throws Exception {
        var path = ledger();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> {
                start.await();
                return new DatasetIntervalLock(path).acquire("run-a", DatasetIntervalLock.Scope.allDates("daily"));
            });
            var second = pool.submit(() -> {
                start.await();
                return new DatasetIntervalLock(path).acquire("run-b", DatasetIntervalLock.Scope.allDates("daily"));
            });
            start.countDown();
            assertEquals(1, (first.get() == null ? 0 : 1) + (second.get() == null ? 0 : 1));
        }
    }
}
