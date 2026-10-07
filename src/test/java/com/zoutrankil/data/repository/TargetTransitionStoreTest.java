package com.zoutrankil.data.repository;

import com.zoutrankil.data.stock.storage.StockSuspendTargetTransitionStore;

import com.zoutrankil.data.service.DcIndexPublication;
import com.zoutrankil.data.stock.application.StockSuspendPublication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TargetTransitionStoreTest {
    @TempDir Path root;

    @Test void dcIndexPendingAndVerifiedTransitionsSurviveReopen() throws Exception {
        Path path = root.resolve("dc.sqlite");
        var store = new DcIndexTargetTransitionStore(path);
        store.initialize();
        var intent = dc("publication", "run", "logical");
        store.insertPending(intent);
        var saved = new DcIndexTargetTransitionStore(path).forRun("run", "logical").orElseThrow();
        assertEquals(intent, saved.intent());
        assertEquals("PENDING", saved.state());
        assertFalse(saved.createdAt().isBlank());
        assertEquals(saved, store.forPublication("publication").orElseThrow());
        assertTrue(store.forRun("run", "another-target").isEmpty());
        assertTrue(store.forPublication("missing").isEmpty());
        assertThrows(SQLException.class, () -> store.insertPending(intent));
        store.markVerified("run");
        store.markVerified("run");
        var reopened = new DcIndexTargetTransitionStore(path).forRun("run", "logical").orElseThrow();
        assertEquals("VERIFIED", reopened.state());
        assertEquals(saved.intent(), reopened.intent());
        assertEquals(saved.createdAt(), reopened.createdAt());
    }

    @Test void stockSuspendPendingAndVerifiedTransitionsSurviveReopen() throws Exception {
        Path path = root.resolve("suspend.sqlite");
        var store = new StockSuspendTargetTransitionStore(path);
        store.initialize();
        var intent = suspend("publication", "run", "logical");
        store.insertPending(intent);
        var saved = new StockSuspendTargetTransitionStore(path).forRun("run", "logical").orElseThrow();
        assertEquals(intent, saved.intent());
        assertEquals("PENDING", saved.state());
        assertFalse(saved.createdAt().isBlank());
        assertEquals(saved, store.forPublication("publication").orElseThrow());
        assertTrue(store.forRun("run", "another-target").isEmpty());
        assertTrue(store.forPublication("missing").isEmpty());
        assertThrows(SQLException.class, () -> store.insertPending(intent));
        store.markVerified("run");
        store.markVerified("run");
        var reopened = new StockSuspendTargetTransitionStore(path).forRun("run", "logical").orElseThrow();
        assertEquals("VERIFIED", reopened.state());
        assertEquals(saved.intent(), reopened.intent());
        assertEquals(saved.createdAt(), reopened.createdAt());
    }

    @Test void dcIndexLineageUsesCreatedAtThenInsertionOrderWithinTheLogicalTarget() throws Exception {
        Path path = root.resolve("dc-order.sqlite");
        var store = new DcIndexTargetTransitionStore(path);
        store.initialize();
        store.insertPending(dc("z", "run-z", "logical"));
        store.insertPending(dc("a", "run-a", "logical"));
        store.insertPending(dc("older", "run-older", "logical"));
        store.insertPending(dc("other", "run-other", "other-logical"));
        freezeOrder(path, "dc_index_target_transitions");
        assertEquals(List.of("run-older", "run-z", "run-a"),
                new DcIndexTargetTransitionStore(path).lineage("logical").stream().map(e -> e.intent().runId()).toList());
        assertTrue(store.lineage("missing").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> store.lineage("logical").clear());
    }

    @Test void stockSuspendLineageUsesCreatedAtThenInsertionOrderWithinTheLogicalTarget() throws Exception {
        Path path = root.resolve("suspend-order.sqlite");
        var store = new StockSuspendTargetTransitionStore(path);
        store.initialize();
        store.insertPending(suspend("z", "run-z", "logical"));
        store.insertPending(suspend("a", "run-a", "logical"));
        store.insertPending(suspend("older", "run-older", "logical"));
        store.insertPending(suspend("other", "run-other", "other-logical"));
        freezeOrder(path, "stk_suspend_target_transitions");
        assertEquals(List.of("run-older", "run-z", "run-a"),
                new StockSuspendTargetTransitionStore(path).lineage("logical").stream().map(e -> e.intent().runId()).toList());
        assertTrue(store.lineage("missing").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> store.lineage("logical").clear());
    }

    @Test void missingLedgerGuardsNeverCreateTheLedgerOrItsParentDirectory() throws Exception {
        Path path = root.resolve("missing/ledger.sqlite");
        assertEquals(new DcIndexTargetTransitionStore.Pending(null, null), new DcIndexTargetTransitionStore(path).pendingIfPresent());
        assertEquals(new StockSuspendTargetTransitionStore.Pending(null, null), new StockSuspendTargetTransitionStore(path).pendingIfPresent());
        DcIndexPublication.requireNoPendingPublication(path);
        StockSuspendPublication.requireNoPendingPublication(path);
        assertFalse(DcIndexPublication.authorizesResume(path, "run", "logical", "before", "after"));
        assertFalse(StockSuspendPublication.authorizesResume(path, "run", "logical", "before", "after"));
        assertFalse(Files.exists(path.getParent()));
    }

    @Test void absentTableGuardsPreserveEachPublicationsExistingInitializationPolicy() throws Exception {
        Path path = root.resolve("existing.sqlite");
        new SyncRunLedger(path);
        Set<String> before = tables(path);
        assertEquals(new DcIndexTargetTransitionStore.Pending(null, null), new DcIndexTargetTransitionStore(path).pendingIfPresent());
        assertEquals(new StockSuspendTargetTransitionStore.Pending(null, null), new StockSuspendTargetTransitionStore(path).pendingIfPresent());
        StockSuspendPublication.requireNoPendingPublication(path);
        assertEquals(before, tables(path));
        DcIndexPublication.requireNoPendingPublication(path);
        var after = new HashSet<>(before);
        after.add("reference_publications"); after.add("dc_index_target_transitions");
        assertEquals(after, tables(path));
    }

    @Test void guardsScopePublicationsByDatasetAndPrioritizeThemBeforeTransitions() throws Exception {
        Path path = root.resolve("pending.sqlite");
        new SyncRunLedger(path);
        var dc = new DcIndexTargetTransitionStore(path); dc.initialize();
        var suspend = new StockSuspendTargetTransitionStore(path); suspend.initialize();
        dc.insertPending(dc("dc-transition", "dc-run", "logical"));
        suspend.insertPending(suspend("suspend-transition", "suspend-run", "logical"));
        var dcJournal = publication(path, "dc_index", "dc-publication-run");
        var suspendJournal = publication(path, "stk_suspend", "suspend-publication-run");
        publication(path, "unrelated", "unrelated-run");
        assertEquals(new DcIndexTargetTransitionStore.Pending("dc-publication-run", null), dc.pendingIfPresent());
        assertEquals(new StockSuspendTargetTransitionStore.Pending("suspend-publication-run", null), suspend.pendingIfPresent());
        assertEquals("dc-publication-run", dc.firstUnverifiedPublication(null));
        assertNull(dc.firstUnverifiedPublication("dc-publication-run"));
        assertEquals("suspend-publication-run", suspend.firstUnverifiedPublication());
        assertEquals("dc-run", dc.firstUnverifiedTransition(null));
        assertNull(dc.firstUnverifiedTransition("dc-run"));
        verify(dcJournal, "dc-publication-run"); verify(suspendJournal, "suspend-publication-run");
        assertEquals(new DcIndexTargetTransitionStore.Pending(null, "dc-run"), dc.pendingIfPresent());
        assertEquals(new StockSuspendTargetTransitionStore.Pending(null, "suspend-run"), suspend.pendingIfPresent());
        dc.markVerified("dc-run"); suspend.markVerified("suspend-run");
        assertEquals(new DcIndexTargetTransitionStore.Pending(null, null), dc.pendingIfPresent());
        assertEquals(new StockSuspendTargetTransitionStore.Pending(null, null), suspend.pendingIfPresent());
    }

    @Test void verificationRequiresExactlyOneMatchingRow() throws Exception {
        Path path = root.resolve("row-count.sqlite");
        var dc = new DcIndexTargetTransitionStore(path); dc.initialize();
        var suspend = new StockSuspendTargetTransitionStore(path); suspend.initialize();
        assertEquals("D023 transition journal missing/ambiguous", assertThrows(IllegalStateException.class,
                () -> dc.markVerified("absent")).getMessage());
        assertEquals("stk_suspend transition journal is absent/ambiguous", assertThrows(IllegalStateException.class,
                () -> suspend.markVerified("absent")).getMessage());
        // A damaged pre-existing schema must not turn an ambiguous update into successful recovery.
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = db.createStatement()) {
            for (String table : List.of("dc_index_target_transitions", "stk_suspend_target_transitions")) {
                statement.execute("DROP TABLE " + table);
                statement.execute("CREATE TABLE " + table + "(run_id TEXT,state TEXT)");
                statement.execute("INSERT INTO " + table + " VALUES('duplicate','PENDING'),('duplicate','PENDING'),('invalid','IN_DOUBT')");
            }
        }
        assertThrows(IllegalStateException.class, () -> dc.markVerified("duplicate"));
        assertThrows(IllegalStateException.class, () -> suspend.markVerified("duplicate"));
        assertThrows(IllegalStateException.class, () -> dc.markVerified("invalid"));
        assertThrows(IllegalStateException.class, () -> suspend.markVerified("invalid"));
    }

    private static DcIndexTargetTransitionStore.Intent dc(String publication, String run, String logical) {
        return new DcIndexTargetTransitionStore.Intent(publication, run, logical, "physical-before", "physical-after", "2026-09-01", "2026-09-30");
    }

    private static StockSuspendTargetTransitionStore.Intent suspend(String publication, String run, String logical) {
        return new StockSuspendTargetTransitionStore.Intent(publication, run, logical, "physical-before", "physical-after",
                "directory-before", "directory-after", "2026-09-01", "2026-09-30", "fingerprint-before", "fingerprint-after");
    }

    private static void freezeOrder(Path path, String table) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = db.createStatement()) {
            statement.executeUpdate("UPDATE " + table + " SET created_at='2026-09-02T00:00:00Z'");
            statement.executeUpdate("UPDATE " + table + " SET created_at='2026-09-01T00:00:00Z' WHERE run_id='run-older'");
        }
    }

    private static Set<String> tables(Path path) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = db.createStatement();
             var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            var names = new HashSet<String>();
            while (rows.next()) names.add(rows.getString(1));
            return names;
        }
    }

    private static ReferencePublicationJournal publication(Path path, String dataset, String run) throws Exception {
        new SyncRunLedger(path).createRun(new SyncRunLedger.Run(run, null, "data.test", 1, "2026-09-01", "logical", "{}"));
        var journal = new ReferencePublicationJournal(path, dataset);
        journal.create(new ReferencePublicationJournal.Intent("publication-" + run, dataset, run, "target_table", "backup_table", "stage_table",
                "logical", 1, "original-directory", 2, "before-fingerprint", "after-fingerprint"));
        return journal;
    }

    private static void verify(ReferencePublicationJournal journal, String run) throws Exception {
        var entry = journal.forRun(run);
        entry = journal.advance(entry, ReferencePublicationJournal.State.OLD_MOVED);
        entry = journal.advance(entry, ReferencePublicationJournal.State.PUBLISHED);
        journal.advance(entry, ReferencePublicationJournal.State.VERIFIED);
    }
}
