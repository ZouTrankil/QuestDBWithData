package com.zoutrankil.batch;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;

import static com.zoutrankil.batch.BatchLedgerTestDatabase.stored;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite rollback and restart checks for the cross-database write protocol. */
class BatchWriteLedgerTest {
    @TempDir Path temporary;
    private BatchLedgerTestDatabase database;
    private DurableWriter.Intent intent;

    @BeforeEach void initialize() throws Exception {
        database = BatchLedgerTestDatabase.create(temporary);
        var request = database.register("test-request");
        intent = database.intent("batch-one", request.instanceId(), "jdb_test_atomic_write");
    }

    @ParameterizedTest @ValueSource(strings = {"write_intent", "target_reservation"})
    void intentAndReservationAreRolledBackTogetherWhenEitherInsertFails(String table) {
        database.jdbc.execute("CREATE TRIGGER fail_insert BEFORE INSERT ON " + table
                + " BEGIN SELECT RAISE(ABORT,'injected insertion failure'); END");

        assertThrows(DataAccessException.class, () -> database.ledger.reserveWriteIntent(stored(intent)));
        var reopened = database.reopen();
        assertEquals(0, reopened.count("write_intent"));
        assertEquals(0, reopened.count("target_reservation"));
    }

    @Test void competingTargetReservationRollsBackTheNewIntent() throws Exception {
        database.ledger.reserveWriteIntent(stored(intent));
        var other = database.intent("batch-two", intent.instanceId(), intent.target());

        var failure = assertThrowsExactly(IllegalStateException.class,
                () -> database.ledger.reserveWriteIntent(stored(other)));
        assertEquals("Target has unresolved sender: batch-one", failure.getMessage());
        var reopened = database.reopen();
        assertEquals(1, reopened.count("write_intent"));
        assertEquals(1, reopened.count("target_reservation"));
        assertEquals("batch-one", reservationOwner(reopened));
    }

    @Test void repeatedIntentKeepsOneReservationAndVerifiedRetryDoesNotReacquireIt() throws Exception {
        database.ledger.reserveWriteIntent(stored(intent));
        database.reopen().ledger.reserveWriteIntent(stored(intent));
        assertEquals(1, database.count("write_intent"));
        assertEquals(1, database.count("target_reservation"));
        database.ledger.verifyWriteAndRelease(intent.batchId(), intent.target(), "{\"verified\":true}");
        var other = database.intent("batch-two", intent.instanceId(), intent.target());
        database.ledger.reserveWriteIntent(stored(other));

        assertDoesNotThrow(() -> database.reopen().ledger.reserveWriteIntent(stored(intent)));
        assertEquals("VERIFIED", database.ledger.writeDelivery(intent.batchId()));
        assertEquals("batch-two", reservationOwner(database));
        assertEquals(2, database.count("write_intent"));
    }

    @ParameterizedTest @ValueSource(strings = {"instance", "target", "owner", "fingerprint", "artifact", "rows"})
    void reusedBatchIdRejectsEveryChangedIdentityFieldWithoutChangingStoredContent(String field) {
        var original = stored(intent);
        database.ledger.reserveWriteIntent(original);
        var changed = new SqliteLedger.WriteIntentRecord(original.batchId(),
                field.equals("instance") ? "different-instance" : original.instanceId(),
                field.equals("target") ? "jdb_test_other_target" : original.target(),
                field.equals("owner") ? "different-owner" : original.owner(),
                field.equals("fingerprint") ? "0".repeat(64) : original.sourceFingerprint(),
                field.equals("artifact") ? "different-artifact" : original.artifact(),
                field.equals("rows") ? 2 : original.expectedRows());

        var failure = assertThrowsExactly(IllegalArgumentException.class,
                () -> database.ledger.reserveWriteIntent(changed));
        assertEquals("Batch identity reused for different content", failure.getMessage());
        assertEquals(1, database.count("write_intent"));
        assertEquals(1, database.count("target_reservation"));
        var row = database.reopen().jdbc.queryForMap("SELECT * FROM write_intent WHERE batch_id=?", intent.batchId());
        assertEquals(intent.instanceId(), row.get("instance_id"));
        assertEquals(intent.target(), row.get("target"));
        assertEquals(intent.owner(), row.get("owner"));
        assertEquals(intent.sourceFingerprint(), row.get("source_fingerprint"));
        assertEquals(intent.artifact(), row.get("artifact"));
        assertEquals(intent.expectedRows(), ((Number) row.get("expected_rows")).longValue());
    }

    @Test void sendObservesCommittedUnknownAndAttemptOneThroughIndependentDataSource() throws Exception {
        var sends = new AtomicInteger();
        var port = new Port() {
            @Override public void send(DurableWriter.Intent supplied) {
                sends.incrementAndGet();
                var independent = database.reopen();
                var state = independent.jdbc.queryForMap("SELECT delivery,attempt FROM write_intent WHERE batch_id=?", supplied.batchId());
                assertEquals("UNKNOWN", state.get("delivery"));
                assertEquals(1, ((Number) state.get("attempt")).intValue());
                assertEquals(supplied.batchId(), reservationOwner(independent));
            }
        };

        assertEquals(DurableWriter.Delivery.ACKNOWLEDGED, new DurableWriter(database.ledger).execute(intent, port));
        assertEquals(1, sends.get());
        assertEquals("ACKNOWLEDGED", database.reopen().ledger.writeDelivery(intent.batchId()));
        assertEquals(1, database.count("target_reservation"));
    }

    @Test void failedUnknownCommitPreventsSendAndLeavesIntentWithZeroAttempts() {
        database.jdbc.execute("""
                CREATE TRIGGER fail_unknown BEFORE UPDATE ON write_intent WHEN NEW.delivery='UNKNOWN'
                BEGIN SELECT RAISE(ABORT,'injected UNKNOWN failure'); END
                """);
        var port = new Port() {
            @Override public void send(DurableWriter.Intent supplied) { fail("Uncommitted UNKNOWN must never send"); }
            @Override public DurableWriter.Proof inspect(DurableWriter.Intent supplied) { return fail("Failed claim must not inspect"); }
        };

        assertThrows(DataAccessException.class, () -> new DurableWriter(database.ledger).execute(intent, port));
        assertEquals("INTENT", database.reopen().ledger.writeDelivery(intent.batchId()));
        assertEquals(0, attempt());
        assertEquals(1, database.count("target_reservation"));
    }

    @Test void lostUnknownClaimPreventsSendWhenStateChangedAfterPreflight() {
        var port = new Port() {
            @Override public void preflight(DurableWriter.Intent supplied) {
                assertEquals(1, database.reopen().ledger.claimWriteUnknown(supplied.batchId()));
            }
            @Override public void send(DurableWriter.Intent supplied) { fail("A zero-row claim must never send"); }
            @Override public DurableWriter.Proof inspect(DurableWriter.Intent supplied) { return fail("A lost claim must not inspect"); }
        };

        var failure = assertThrowsExactly(IllegalStateException.class,
                () -> new DurableWriter(database.ledger).execute(intent, port));
        assertEquals("Write intent no longer sendable", failure.getMessage());
        assertEquals("UNKNOWN", database.reopen().ledger.writeDelivery(intent.batchId()));
        assertEquals(1, attempt());
        assertEquals(1, database.count("target_reservation"));
    }

    @Test void sendFailureReopensAsUnknownAndIsNeverResent() throws Exception {
        var sends = new AtomicInteger();
        var failedSend = new Port() {
            @Override public void send(DurableWriter.Intent supplied) throws IOException {
                sends.incrementAndGet();
                throw new IOException("ACK lost\nwith diagnostic");
            }
            @Override public DurableWriter.Proof inspect(DurableWriter.Intent supplied) { return fail("Uncertain send returns before inspection"); }
        };
        assertEquals(DurableWriter.Delivery.UNKNOWN, new DurableWriter(database.ledger).execute(intent, failedSend));
        var reopened = database.reopen();
        assertEquals("UNKNOWN", reopened.ledger.writeDelivery(intent.batchId()));
        assertEquals("batch-one:IOException:ACK lost with diagnostic", reopened.jdbc.queryForObject(
                "SELECT detail FROM audit_event WHERE action='delivery-unknown'", String.class));

        assertEquals(DurableWriter.Delivery.UNKNOWN, new DurableWriter(reopened.ledger).execute(intent, reconciliationOnly()));
        assertEquals(1, sends.get());
        assertEquals(1, attempt());
        assertEquals(1, database.count("target_reservation"));
    }

    @Test void failedAcknowledgementCommitLeavesUnknownAfterTheOneSend() {
        database.jdbc.execute("""
                CREATE TRIGGER fail_ack BEFORE UPDATE ON write_intent WHEN NEW.delivery='ACKNOWLEDGED'
                BEGIN SELECT RAISE(ABORT,'injected ACK failure'); END
                """);
        var sends = new AtomicInteger();
        var port = new Port() {
            @Override public void send(DurableWriter.Intent supplied) { sends.incrementAndGet(); }
            @Override public DurableWriter.Proof inspect(DurableWriter.Intent supplied) { return fail("Failed ACK commit must not inspect"); }
        };

        assertThrows(DataAccessException.class, () -> new DurableWriter(database.ledger).execute(intent, port));
        assertEquals(1, sends.get());
        assertEquals("UNKNOWN", database.reopen().ledger.writeDelivery(intent.batchId()));
        assertEquals(1, attempt());
        assertEquals(1, database.count("target_reservation"));
    }

    @Test void acknowledgedButInvisibleBoundaryKeepsReservationAndDoesNotResend() throws Exception {
        var sends = new AtomicInteger();
        var port = new Port() {
            @Override public void send(DurableWriter.Intent supplied) { sends.incrementAndGet(); }
            @Override public DurableWriter.Proof inspect(DurableWriter.Intent supplied) {
                return new DurableWriter.Proof(true, false, true, false, "proof://invisible-boundary");
            }
        };
        assertEquals(DurableWriter.Delivery.ACKNOWLEDGED, new DurableWriter(database.ledger).execute(intent, port));
        assertEquals(DurableWriter.Delivery.ACKNOWLEDGED, new DurableWriter(database.reopen().ledger).execute(intent, port));
        assertEquals(1, sends.get());
        assertEquals(1, attempt());
        assertEquals(intent.batchId(), reservationOwner(database));
    }

    @Test void unknownCannotVerifyVisibleContentUntilSenderHasStopped() throws Exception {
        database.ledger.reserveWriteIntent(stored(intent));
        assertEquals(1, database.ledger.claimWriteUnknown(intent.batchId()));
        var port = new Port() {
            @Override public void preflight(DurableWriter.Intent supplied) { fail("UNKNOWN must not preflight"); }
            @Override public void send(DurableWriter.Intent supplied) { fail("UNKNOWN must not send"); }
            @Override public DurableWriter.Proof inspect(DurableWriter.Intent supplied) {
                return new DurableWriter.Proof(true, true, false, false, "proof://sender-still-running");
            }
        };

        assertEquals(DurableWriter.Delivery.UNKNOWN, new DurableWriter(database.reopen().ledger).execute(intent, port));
        assertEquals(intent.batchId(), reservationOwner(database));
        assertEquals(1, attempt());
    }

    @ParameterizedTest @ValueSource(strings = {"verify", "release"})
    void verifiedStateAndReservationReleaseRollBackTogether(String failurePoint) {
        database.jdbc.execute(failurePoint.equals("verify") ? """
                CREATE TRIGGER fail_finish BEFORE UPDATE ON write_intent WHEN NEW.delivery='VERIFIED'
                BEGIN SELECT RAISE(ABORT,'injected verification failure'); END
                """ : """
                CREATE TRIGGER fail_finish BEFORE DELETE ON target_reservation
                BEGIN SELECT RAISE(ABORT,'injected release failure'); END
                """);
        var port = new Port() {
            @Override public DurableWriter.Proof inspect(DurableWriter.Intent supplied) { return visibleProof(false); }
        };

        assertThrows(DataAccessException.class, () -> new DurableWriter(database.ledger).execute(intent, port));
        var reopened = database.reopen();
        assertEquals("ACKNOWLEDGED", reopened.ledger.writeDelivery(intent.batchId()));
        assertEquals(intent.batchId(), reservationOwner(reopened));
        assertNull(reopened.jdbc.queryForObject("SELECT proof FROM write_intent WHERE batch_id=?", String.class, intent.batchId()));
        database.jdbc.execute("DROP TRIGGER fail_finish");
        assertDoesNotThrow(() -> assertEquals(DurableWriter.Delivery.VERIFIED,
                new DurableWriter(reopened.ledger).execute(intent, new Port() {
                    @Override public void preflight(DurableWriter.Intent supplied) { fail("ACK must not preflight again"); }
                    @Override public void send(DurableWriter.Intent supplied) { fail("ACK must not send again"); }
                    @Override public DurableWriter.Proof inspect(DurableWriter.Intent supplied) { return visibleProof(false); }
                })));
        assertEquals(0, reopened.count("target_reservation"));
    }

    @Test void blockedWritesKeepReservationAndCannotBeAutomaticallyResumed() throws Exception {
        var proof = visibleProof(true);
        var port = new Port() {
            @Override public DurableWriter.Proof inspect(DurableWriter.Intent supplied) { return proof; }
        };
        assertEquals(DurableWriter.Delivery.BLOCKED, new DurableWriter(database.ledger).execute(intent, port));
        assertEquals(intent.batchId(), reservationOwner(database));
        assertEquals(Json.write(proof), database.jdbc.queryForObject("SELECT proof FROM write_intent WHERE batch_id=?", String.class, intent.batchId()));
        assertEquals(DurableWriter.Delivery.BLOCKED, new DurableWriter(database.reopen().ledger).execute(intent, new Port() {
            @Override public void preflight(DurableWriter.Intent supplied) { fail("BLOCKED must not preflight"); }
            @Override public void send(DurableWriter.Intent supplied) { fail("BLOCKED must not send"); }
            @Override public DurableWriter.Proof inspect(DurableWriter.Intent supplied) { return fail("BLOCKED requires operator recovery"); }
        }));
        var other = database.intent("batch-two", intent.instanceId(), intent.target());
        assertThrowsExactly(IllegalStateException.class, () -> database.ledger.reserveWriteIntent(stored(other)));
        assertEquals(1, database.count("write_intent"));
    }

    @Test void businessCreationTimeKeepsTheOriginalJdbcInstantAcrossReopenAndRegistration() {
        database.jdbc.update("UPDATE business_instance SET created_at='2026-09-29 10:30:00.123' WHERE instance_id=?", intent.instanceId());
        Instant original = database.reopen().jdbc.queryForObject("SELECT created_at FROM business_instance WHERE instance_id=?",
                (rows, index) -> rows.getTimestamp(1).toInstant(), intent.instanceId());
        assertEquals(original, database.ledger.businessCreatedAt(intent.instanceId()));
        var reopened = database.reopen();
        reopened.register("later-trigger-for-same-business-instance");
        assertEquals(original, reopened.ledger.businessCreatedAt(intent.instanceId()));
    }

    private int attempt() {
        return database.reopen().jdbc.queryForObject("SELECT attempt FROM write_intent WHERE batch_id=?", Integer.class, intent.batchId());
    }

    private String reservationOwner(BatchLedgerTestDatabase db) {
        return db.jdbc.queryForObject("SELECT batch_id FROM target_reservation WHERE target=?", String.class, intent.target());
    }

    private static DurableWriter.Proof visibleProof(boolean suspended) {
        return new DurableWriter.Proof(true, true, true, suspended, "proof://exact-content");
    }

    private static Port reconciliationOnly() {
        return new Port() {
            @Override public void preflight(DurableWriter.Intent intent) { fail("UNKNOWN must not preflight again"); }
            @Override public void send(DurableWriter.Intent intent) { fail("UNKNOWN must never resend"); }
        };
    }

    private static class Port implements DurableWriter.Port {
        @Override public void preflight(DurableWriter.Intent intent) throws Exception {}
        @Override public void send(DurableWriter.Intent intent) throws Exception {}
        @Override public DurableWriter.Proof inspect(DurableWriter.Intent intent) throws Exception {
            return new DurableWriter.Proof(false, false, false, false, null);
        }
    }
}
