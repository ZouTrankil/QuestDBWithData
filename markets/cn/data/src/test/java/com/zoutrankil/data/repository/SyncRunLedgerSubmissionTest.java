package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.SyncRunState;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SyncRunLedgerSubmissionTest {
    @TempDir Path temp;
    private static final String PAYLOAD = "{\"sourceFingerprint\":\"frozen-source\",\"returnedRows\":1}";

    @Test void realSubmissionEventIsRequiredWithoutChangingItsProjection() throws Exception {
        var ledger = ledger("valid");
        createRun(ledger, "run", null);
        submit(ledger, "run");
        var before = ledger.get("slice");
        var events = ledger.events("slice", -1, 10);
        assertDoesNotThrow(() -> check(ledger, "run"));
        assertEquals(before, ledger.get("slice"));
        assertEquals(events, ledger.events("slice", -1, 10));
    }

    @Test void tamperedMissingOrDuplicateEventCannotBeReplacedByTheValidProjection() throws Exception {
        String[] corruptions = {
                "UPDATE sync_events SET payload_json='{}' WHERE entry_id='slice' AND revision=4",
                "UPDATE sync_events SET state='VALIDATED' WHERE entry_id='slice' AND revision=4",
                "DELETE FROM sync_events WHERE entry_id='slice' AND revision=4"
        };
        for (int i = 0; i < corruptions.length; i++) {
            var ledger = ledger("tampered-" + i);
            createRun(ledger, "run", null);
            submit(ledger, "run");
            sql(ledger, corruptions[i]);
            assertEquals(SyncRunState.SUBMITTED, ledger.get("slice").state());
            assertEquals(PAYLOAD, ledger.get("slice").payloadJson());
            assertEquals("Revision4 submission event differs",
                    assertThrows(IllegalStateException.class, () -> check(ledger, "run")).getMessage());
        }
        var duplicate = ledger("duplicate");
        createRun(duplicate, "run", null);
        submit(duplicate, "run");
        sql(duplicate, "CREATE TABLE copied_events AS SELECT * FROM sync_events",
                "DROP TABLE sync_events", "ALTER TABLE copied_events RENAME TO sync_events",
                "INSERT INTO sync_events SELECT * FROM sync_events WHERE entry_id='slice' AND revision=4");
        assertEquals("Revision4 submission event differs",
                assertThrows(IllegalStateException.class, () -> check(duplicate, "run")).getMessage());
    }

    @Test void cancellationOfTheRunOrEitherAncestorIsRejected() throws Exception {
        for (String cancelled : new String[]{"grandparent", "parent", "child"}) {
            var ledger = ledger(cancelled);
            createRun(ledger, "grandparent", null);
            createRun(ledger, "parent", "grandparent");
            createRun(ledger, "child", "parent");
            submit(ledger, "child");
            assertDoesNotThrow(() -> check(ledger, "child"));
            assertTrue(ledger.requestCancellation(cancelled));
            assertEquals("Run or parent cancelled",
                    assertThrows(IllegalStateException.class, () -> check(ledger, "child")).getMessage());
        }
    }

    @Test void unrelatedCancellationDoesNotBlockTheSubmission() throws Exception {
        var ledger = ledger("unrelated");
        createRun(ledger, "run", null);
        createRun(ledger, "other", null);
        submit(ledger, "run");
        ledger.requestCancellation("other");
        assertDoesNotThrow(() -> check(ledger, "run"));
    }

    @Test void exactly64OwnersAreAllowedAnd65AreRejected() throws Exception {
        var ledger = ledger("bounded");
        for (int i = 0; i < 64; i++) createRun(ledger, "run-" + i, i == 0 ? null : "run-" + (i - 1));
        submit(ledger, "run-63");
        assertDoesNotThrow(() -> check(ledger, "run-63"));
        createRun(ledger, "extra-parent", null);
        sql(ledger, "UPDATE sync_runs SET parent_run_id='extra-parent' WHERE id='run-0'");
        assertEquals("Parent cancellation chain is cyclic or exceeds budget",
                assertThrows(IllegalStateException.class, () -> check(ledger, "run-63")).getMessage());
    }

    @Test void cyclicParentChainIsRejected() throws Exception {
        var ledger = ledger("cycle");
        createRun(ledger, "parent", null);
        createRun(ledger, "child", "parent");
        submit(ledger, "child");
        sql(ledger, "UPDATE sync_runs SET parent_run_id='child' WHERE id='parent'");
        assertEquals("Parent cancellation chain is cyclic or exceeds budget",
                assertThrows(IllegalStateException.class, () -> check(ledger, "child")).getMessage());
    }

    @Test void missingParentPreservesTheLedgerAuthorityFailure() throws Exception {
        var ledger = ledger("missing-parent");
        createRun(ledger, "child", null);
        submit(ledger, "child");
        sql(ledger, "UPDATE sync_runs SET parent_run_id='missing' WHERE id='child'");
        assertEquals("Unknown run",
                assertThrows(IllegalArgumentException.class, () -> check(ledger, "child")).getMessage());
    }

    private SyncRunLedger ledger(String name) throws Exception {
        return new SyncRunLedger(temp.resolve(name + ".sqlite"));
    }

    private static void createRun(SyncRunLedger ledger, String id, String parent) throws Exception {
        ledger.createRun(new SyncRunLedger.Run(id, parent, "data.fixture", 1, "2026-09-29", "fixture-target", "{}"));
    }

    private static void submit(SyncRunLedger ledger, String run) throws Exception {
        ledger.transition(run, 0, SyncRunState.RUNNING, "{}");
        ledger.createChild("attempt", SyncRunLedger.Kind.ATTEMPT, run, run);
        ledger.createChild("slice", SyncRunLedger.Kind.SLICE, run, "attempt");
        ledger.transition("slice", 0, SyncRunState.RUNNING, "{}");
        ledger.transition("slice", 1, SyncRunState.FETCHED, PAYLOAD);
        ledger.transition("slice", 2, SyncRunState.VALIDATED, PAYLOAD);
        ledger.transition("slice", 3, SyncRunState.SUBMITTED, PAYLOAD);
    }

    private static void check(SyncRunLedger ledger, String run) throws Exception {
        SyncRunLedger.openReadOnly(ledger.path()).requireUncancelledRevision4SubmissionEvent(run, "slice", PAYLOAD);
    }

    private static void sql(SyncRunLedger ledger, String... statements) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + ledger.path());
             var statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }
}
