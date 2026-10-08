package com.zoutrankil.batch;

import com.zoutrankil.data.service.TusharePageService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Probe persistence is independent of collection and keeps the original retry and audit ordering. */
class NativeSourceProbeLedgerTest {
    @TempDir Path temporary;
    private BatchLedgerTestDatabase database;
    private SourceCollector collector;
    private TusharePageService pages;
    private SourceCollector.Request request;
    private SourceCollector.Collected collected;

    @BeforeEach void initialize() throws Exception {
        database = BatchLedgerTestDatabase.create(temporary);
        collector = mock(SourceCollector.class);
        pages = mock(TusharePageService.class);
        request = new SourceCollector.Request("daily", LocalDate.of(2026, 9, 29), Set.of("000001.SZ"),
                "test-universe-v1", "000001.SZ");
        collected = new SourceCollector.Collected("a".repeat(64), temporary.resolve("frozen-source.json").toString(),
                1, 1, true, "b".repeat(64), BusinessState.VERIFYING);
    }

    @Test void newlyClaimedProbeKeepsNullableColumnsAndRepeatClaimDoesNotAcquire() {
        var claimed = database.ledger.claimSourceProbe("probe-one", fingerprint(), Json.write(request));

        assertTrue(claimed.acquired());
        assertEquals("RUNNING", claimed.row().get("state"));
        assertNullableColumns(claimed.row());
        assertNotNull(claimed.row().get("started_at"));
        var repeated = database.reopen().ledger.claimSourceProbe("probe-one", fingerprint(), Json.write(request));
        assertFalse(repeated.acquired());
        assertEquals(claimed.row(), repeated.row());
        assertEquals(1, database.count("source_probe"));
    }

    @Test void conflictingFingerprintFailsBeforeCollectionWithoutChangingStoredProbe() throws Exception {
        database.ledger.claimSourceProbe("probe-one", fingerprint(), Json.write(request));
        var before = database.ledger.sourceProbe("probe-one");
        var changed = new SourceCollector.Request(request.dataset(), request.logicalDate(), request.expectedCodes(),
                "different-universe-v2", request.tsCode());

        var failure = assertThrowsExactly(IllegalArgumentException.class,
                () -> service(database.reopen()).collect("probe-one", changed));
        assertEquals("Source probe idempotency key reused for different input", failure.getMessage());
        assertEquals(before, database.reopen().ledger.sourceProbe("probe-one"));
        verifyNoInteractions(collector, pages);
    }

    @ParameterizedTest @ValueSource(strings = {"RUNNING", "FAILED", "VERIFYING"})
    void existingRunningFailedAndSuccessfullyCollectedProbesNeverCollectAgain(String state) throws Exception {
        database.ledger.claimSourceProbe("probe-one", fingerprint(), Json.write(request));
        if (state.equals("FAILED")) database.ledger.failSourceProbe("probe-one", "IOException");
        if (state.equals("VERIFYING")) database.ledger.completeSourceProbe("probe-one", state, Json.write(collected));
        var original = database.ledger.sourceProbe("probe-one");

        assertEquals(original, service(database.reopen()).collect("probe-one", request));
        assertEquals(state, original.get("state"));
        verifyNoInteractions(collector, pages);
        assertEquals(0, database.count("audit_event"));
    }

    @Test void collectionSeesCommittedRunningProbeAndCompletionPrecedesItsAudit() throws Exception {
        database.jdbc.execute("""
                CREATE TRIGGER require_completed_probe BEFORE INSERT ON audit_event
                WHEN NEW.action='source-collected' AND NOT EXISTS (
                    SELECT 1 FROM source_probe WHERE request_id='probe-one' AND state='VERIFYING'
                      AND result_json IS NOT NULL AND completed_at IS NOT NULL)
                BEGIN SELECT RAISE(ABORT,'completion must precede audit'); END
                """);
        when(collector.collect(eq(request), any(SourceCollector.ContractFetcher.class))).thenAnswer(call -> {
            var independentlyRead = database.reopen().ledger.sourceProbe("probe-one");
            assertEquals("RUNNING", independentlyRead.get("state"));
            assertEquals(fingerprint(), independentlyRead.get("request_fingerprint"));
            assertNullableColumns(independentlyRead);
            return collected;
        });

        var result = service(database).collect("probe-one", request);
        assertEquals("VERIFYING", result.get("state"));
        assertEquals(Json.write(collected), result.get("result_json"));
        assertNull(result.get("error_type"));
        assertNotNull(result.get("completed_at"));
        var audit = database.reopen().jdbc.queryForMap("SELECT instance_id,action,detail FROM audit_event");
        assertNull(audit.get("instance_id"));
        assertEquals("source-collected", audit.get("action"));
        assertEquals("daily:" + collected.fingerprint(), audit.get("detail"));
        assertEquals(result, service(database.reopen()).collect("probe-one", request));
        verify(collector, times(1)).collect(eq(request), any(SourceCollector.ContractFetcher.class));
        assertEquals(1, database.count("audit_event"));
    }

    @Test void collectionFailurePersistsFailedStateAndOriginalExceptionWithoutRetrying() throws Exception {
        var cause = new IOException("provider unavailable");
        when(collector.collect(eq(request), any(SourceCollector.ContractFetcher.class))).thenThrow(cause);

        assertSame(cause, assertThrowsExactly(IOException.class, () -> service(database).collect("probe-one", request)));
        var failed = database.reopen().ledger.sourceProbe("probe-one");
        assertEquals("FAILED", failed.get("state"));
        assertEquals("IOException", failed.get("error_type"));
        assertNull(failed.get("result_json"));
        assertNotNull(failed.get("completed_at"));
        assertEquals(failed, service(database.reopen()).collect("probe-one", request));
        verify(collector, times(1)).collect(eq(request), any(SourceCollector.ContractFetcher.class));
        assertEquals(0, database.count("audit_event"));
    }

    @Test void auditFailureKeepsCommittedResultThenMarksProbeFailedWithoutRecollecting() throws Exception {
        when(collector.collect(eq(request), any(SourceCollector.ContractFetcher.class))).thenReturn(collected);
        database.jdbc.execute("""
                CREATE TRIGGER fail_source_audit BEFORE INSERT ON audit_event WHEN NEW.action='source-collected'
                BEGIN SELECT RAISE(ABORT,'injected source audit failure'); END
                """);

        var failure = assertThrows(DataAccessException.class, () -> service(database).collect("probe-one", request));
        var failed = database.reopen().ledger.sourceProbe("probe-one");
        assertEquals("FAILED", failed.get("state"));
        assertEquals(Json.write(collected), failed.get("result_json"));
        assertEquals(failure.getClass().getSimpleName(), failed.get("error_type"));
        assertNotNull(failed.get("completed_at"));
        assertEquals(0, database.count("audit_event"));
        assertEquals(failed, service(database.reopen()).collect("probe-one", request));
        verify(collector, times(1)).collect(eq(request), any(SourceCollector.ContractFetcher.class));
    }

    @Test void completionFailureMarksFailedWithoutPublishingResultOrAudit() throws Exception {
        when(collector.collect(eq(request), any(SourceCollector.ContractFetcher.class))).thenReturn(collected);
        database.jdbc.execute("""
                CREATE TRIGGER fail_probe_completion BEFORE UPDATE ON source_probe WHEN NEW.state='VERIFYING'
                BEGIN SELECT RAISE(ABORT,'injected completion failure'); END
                """);

        var failure = assertThrows(DataAccessException.class, () -> service(database).collect("probe-one", request));
        var failed = database.reopen().ledger.sourceProbe("probe-one");
        assertEquals("FAILED", failed.get("state"));
        assertEquals(failure.getClass().getSimpleName(), failed.get("error_type"));
        assertNull(failed.get("result_json"));
        assertEquals(0, database.count("audit_event"));
        assertEquals(failed, service(database.reopen()).collect("probe-one", request));
        verify(collector, times(1)).collect(eq(request), any(SourceCollector.ContractFetcher.class));
    }

    @Test void failureRecordingErrorLeavesRunningClaimAndStillPreventsDuplicateCollection() throws Exception {
        when(collector.collect(eq(request), any(SourceCollector.ContractFetcher.class))).thenThrow(new IOException("provider failed"));
        database.jdbc.execute("""
                CREATE TRIGGER fail_failure_recording BEFORE UPDATE ON source_probe WHEN NEW.state='FAILED'
                BEGIN SELECT RAISE(ABORT,'injected failure-recording failure'); END
                """);

        assertThrows(DataAccessException.class, () -> service(database).collect("probe-one", request));
        var running = database.reopen().ledger.sourceProbe("probe-one");
        assertEquals("RUNNING", running.get("state"));
        assertNullableColumns(running);
        assertEquals(running, service(database.reopen()).collect("probe-one", request));
        verify(collector, times(1)).collect(eq(request), any(SourceCollector.ContractFetcher.class));
        assertEquals(0, database.count("audit_event"));
    }

    @Test void failedClaimDoesNotStartCollectionOrCreateAudit() {
        database.jdbc.execute("""
                CREATE TRIGGER fail_probe_claim BEFORE INSERT ON source_probe
                BEGIN SELECT RAISE(ABORT,'injected claim failure'); END
                """);

        assertThrows(DataAccessException.class, () -> service(database).collect("probe-one", request));
        assertEquals(0, database.count("source_probe"));
        assertEquals(0, database.count("audit_event"));
        verifyNoInteractions(collector, pages);
    }

    @Test void invalidIdempotencyKeyFailsBeforeClaimAndCollection() {
        for (String id : Arrays.asList(null, "", " ", "x".repeat(257))) {
            var failure = assertThrowsExactly(IllegalArgumentException.class, () -> service(database).collect(id, request));
            assertEquals("Idempotency-Key required", failure.getMessage());
        }
        assertEquals(0, database.count("source_probe"));
        verifyNoInteractions(collector, pages);
    }

    private String fingerprint() { return RunRequest.hash(Json.write(request)); }

    private NativeSourceService service(BatchLedgerTestDatabase db) {
        return new NativeSourceService(db.ledger, collector, pages);
    }

    private static void assertNullableColumns(Map<String, Object> row) {
        for (String column : Set.of("result_json", "error_type", "completed_at")) {
            assertTrue(row.containsKey(column), column + " must remain present even when SQL NULL");
            assertNull(row.get(column));
        }
    }
}
