package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.IntervalLockStore;
import com.zoutrankil.data.service.DatasetIntervalLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SqliteIntervalLockStoreTest {
    @TempDir Path root;

    private Path ledger() throws Exception {
        var path = root.resolve("control.sqlite3");
        var ledger = new SyncRunLedger(path);
        for (String run : new String[]{"run-a", "run-b"})
            ledger.createRun(new SyncRunLedger.Run(run, null, "job", 1, "2026-09-29", "isolated", "{}"));
        return path;
    }

    @Test void facadeAndStoreShareTheSameDomainLeaseAndPersistentState() throws Exception {
        var path = ledger();
        var facade = new DatasetIntervalLock(path);
        IntervalLockStore store = new SqliteIntervalLockStore(path);
        DatasetIntervalLock.Scope scope = DatasetIntervalLock.Scope.allDates("daily");
        DatasetIntervalLock.Lease lease = facade.acquire("run-a", scope);
        assertEquals(new IntervalLockStore.Scope("daily", LocalDate.MIN, LocalDate.MAX), scope);
        assertEquals(lease, store.findOwned("run-a", scope));
        assertNull(store.acquire("run-b", scope));
        store.retainInDoubt(lease);
        var retained = facade.findOwned("run-a", scope);
        assertTrue(retained.inDoubt());
        facade.releaseAfterReconciliation(retained, true, true);
        assertNull(store.findOwned("run-a", scope));
        var next = store.acquire("run-b", scope);
        assertNotNull(next);
        facade.releaseVerified(next);
        assertNull(store.findOwned("run-b", scope));
    }

    @Test void missingLedgerKeepsItsOriginalFailureAndIsNotCreated() {
        var missing = root.resolve("missing.sqlite3");
        var direct = assertThrowsExactly(IllegalArgumentException.class, () -> new SqliteIntervalLockStore(missing));
        var facade = assertThrowsExactly(IllegalArgumentException.class, () -> new DatasetIntervalLock(missing));
        assertEquals("Existing run ledger required", direct.getMessage());
        assertEquals(direct.getMessage(), facade.getMessage());
        assertFalse(Files.exists(missing));
    }

    @Test void failedForeignKeyInsertRollsBackAndLeavesTheIntervalAvailable() throws Exception {
        var path = ledger();
        var store = new SqliteIntervalLockStore(path);
        var scope = IntervalLockStore.Scope.allDates("daily");
        var failure = assertThrowsExactly(IllegalStateException.class, () -> store.acquire("unknown-run", scope));
        assertEquals("Cannot acquire interval lock", failure.getMessage());
        assertInstanceOf(SQLException.class, failure.getCause());
        assertNull(store.findOwned("unknown-run", scope));
        var lease = new SqliteIntervalLockStore(path).acquire("run-a", scope);
        assertNotNull(lease);
        store.releaseVerified(lease);
    }

    @Test void ownershipAndStateFailuresRollBackWithoutReleasingTheLease() throws Exception {
        var path = ledger();
        var store = new SqliteIntervalLockStore(path);
        var scope = IntervalLockStore.Scope.allDates("daily");
        var lease = store.acquire("run-a", scope);
        var foreign = new IntervalLockStore.Lease(lease.id(), "run-b", scope, false);
        var ownership = assertThrowsExactly(IllegalStateException.class, () -> store.releaseVerified(foreign));
        assertEquals("Lease ownership or state changed", ownership.getMessage());
        assertNull(ownership.getCause());
        assertEquals(lease, store.findOwned("run-a", scope));

        store.retainInDoubt(lease);
        var state = assertThrowsExactly(IllegalStateException.class, () -> store.releaseVerified(lease));
        assertEquals("Lease ownership or state changed", state.getMessage());
        assertTrue(store.findOwned("run-a", scope).inDoubt());
        var proof = assertThrowsExactly(IllegalStateException.class,
                () -> store.releaseAfterReconciliation(lease, false, true));
        assertEquals("Uncertain writer requires stop and exact readback proof", proof.getMessage());
        assertNull(new SqliteIntervalLockStore(path).acquire("run-b", scope));
        store.releaseAfterReconciliation(lease, true, true);
        assertNull(store.findOwned("run-a", scope));
    }
}
