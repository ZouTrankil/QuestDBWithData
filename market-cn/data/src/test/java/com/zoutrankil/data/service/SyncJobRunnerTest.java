package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

class SyncJobRunnerTest {
    @TempDir Path root;
    record Row(String key, String value) {}
    FrozenRequest request() {
        return request(Duration.ofMinutes(1));
    }
    FrozenRequest request(Duration timeout) {
        return request(timeout, null, null);
    }
    FrozenRequest request(Duration timeout, LocalDate from, LocalDate to) {
        return new SyncJobDefinition("test.job", 1, "test_dataset", 1, "test_adapter",
                Set.of(Mode.SNAPSHOT), Mode.SNAPSHOT, Map.of(), "rate", "slice", "verify",
                new RetryPolicy(1, Duration.ofMillis(1), Duration.ofSeconds(1)), timeout,
                new Budget(from == null ? 1 : 3, 5, 5, 10, 4096), 0, List.of(), Frequency.MANUAL, ZoneOffset.UTC, true, false)
                .freeze(null, Map.of(), from, to, LocalDate.of(2026, 9, 29));
    }
    static class Adapter implements SyncJobRunner.Adapter<Row,String>, VerifiedBatchExecutor.Port<Row,String> {
        final Map<String,Row> stored = new LinkedHashMap<>();
        int fetches, sends;
        boolean failSource, cancelAfterFetch;
        public void preflight(FrozenRequest request) {}
        public SyncJobRunner.SourceCompletion fetch(FrozenRequest request, SyncJobRunner.PageConsumer<Row> consumer,
                                                    BooleanSupplier cancelled) throws Exception {
            fetches++;
            if (failSource) throw new IllegalStateException("Source error");
            consumer.accept(new SyncJobRunner.Page<>(List.of(new Row("a", "1")), "source-a", "response-a", null));
            assertEquals(1, sends, "Page consumer must finish writing before fetch advances");
            consumer.accept(new SyncJobRunner.Page<>(List.of(new Row("b", "2")), "source-b", "response-b", null));
            return new SyncJobRunner.SourceCompletion(2,2,true,"source-complete");
        }
        public VerifiedBatchExecutor.Codec<Row,String> codec() {
            return new VerifiedBatchExecutor.Codec<>() {
                public String key(Row row) { return row.key(); }
                public byte[] canonicalBytes(Row row) { return (row.key()+":"+row.value()).getBytes(StandardCharsets.UTF_8); }
            };
        }
        public VerifiedBatchExecutor.Port<Row,String> port() { return this; }
        public void preflight() {}
        public void send(List<Row> rows) { sends++; rows.forEach(r -> stored.put(r.key(), r)); }
        public List<Row> readback(List<String> keys) { return keys.stream().map(stored::get).filter(Objects::nonNull).toList(); }
        public boolean walSettled() { return true; }
    }
    @Test void delegateSeesCommittedSubmissionBeforeEverySend() throws Exception {
        Path path = root.resolve("delegate.sqlite3");
        var ledger = new SyncRunLedger(path);
        var adapter = new Adapter() {
            VerifiedBatchExecutor.Submission submitted;
            @Override public void submissionRecorded(VerifiedBatchExecutor.Submission context) throws Exception {
                assertEquals(path.toAbsolutePath().normalize(), context.ledgerPath());
                var authority = SyncRunLedger.openReadOnly(context.ledgerPath());
                var entry = authority.get(context.sliceId());
                assertEquals(SyncRunLedger.Kind.SLICE, entry.kind());
                assertEquals(SyncRunState.SUBMITTED, entry.state());
                assertEquals(4, entry.revision());
                assertEquals(entry.revision(), context.revision());
                assertEquals(context.runId(), entry.runId());
                assertTrue(entry.payloadJson().contains(context.sourceFingerprint()));
                assertEquals("delegated", authority.getRun(context.runId()).id());
                submitted = context;
            }
            @Override public void send(List<Row> rows) {
                assertNotNull(submitted, "Committed authority must precede delegate publication");
                super.send(rows);
                submitted = null;
            }
        };
        var result = new SyncJobRunner<Row,String>(ledger, new DatasetIntervalLock(path))
                .run("delegated", null, "target", request(), adapter, () -> false);
        assertEquals(SyncRunState.VERIFIED, result.state());
        assertEquals(2, adapter.sends);
    }
    @Test void failedDelegateSubmissionGuardRetainsUncertaintyWithoutSending() throws Exception {
        Path path = root.resolve("delegate-rejected.sqlite3");
        var ledger = new SyncRunLedger(path);
        var locks = new DatasetIntervalLock(path);
        var adapter = new Adapter() {
            @Override public Duration visibilityTimeout() { return Duration.ofMillis(1); }
            @Override public void submissionRecorded(VerifiedBatchExecutor.Submission context) {
                throw new IllegalStateException("Delegate durable-intent guard rejected");
            }
        };
        var result = new SyncJobRunner<Row,String>(ledger, locks)
                .run("delegate-rejected", null, "target", request(), adapter, () -> false);
        assertEquals(SyncRunState.IN_DOUBT, result.state());
        assertEquals(0, adapter.sends);
        assertNotNull(locks.findOwned("delegate-rejected", adapter.conflictScope(request())));
    }

    @Test void sequentialPagesConvergeIntoLedgerAndPreserveLogicalDate() throws Exception {
        Path path = root.resolve("ledger.sqlite3");
        var ledger = new SyncRunLedger(path); var locks = new DatasetIntervalLock(path);
        var runner = new SyncJobRunner<Row,String>(ledger, locks); var adapter = new Adapter();
        var result = runner.run("run-1", null, "target", request(), adapter, () -> false);
        assertEquals(SyncRunState.VERIFIED, result.state());
        assertEquals(2, result.verifiedRows());
        assertEquals("2026-09-29", ledger.getRun("run-1").logicalDate());
        assertEquals(4, ledger.entries("run-1", null, 10).size());
        assertTrue(ledger.entries("run-1", null, 10).stream().allMatch(e -> e.state() == SyncRunState.VERIFIED));
        assertEquals(SyncRunState.VERIFIED, runner.run("run-2", null, "target", request(), new Adapter(), () -> false).state());
    }
    @Test void defaultConflictScopePreservesExactBoundedWindowAndAllDatesForUnboundedRequest() throws Exception {
        Path path = root.resolve("ledger.sqlite3");
        var ledger = new SyncRunLedger(path); var locks = new DatasetIntervalLock(path);
        var runner = new SyncJobRunner<Row,String>(ledger, locks);
        LocalDate from = LocalDate.of(2026, 9, 27), to = from.plusDays(2);
        int index = 0;
        for (var frozen : List.of(request(), request(Duration.ofMinutes(1), from, to))) {
            String runId = "scope-" + index++;
            var expected = frozen.from() == null ? DatasetIntervalLock.Scope.allDates("test_dataset")
                    : new DatasetIntervalLock.Scope("test_dataset", from, to);
            var adapter = new Adapter() {
                @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,
                        SyncJobRunner.PageConsumer<Row> consumer, BooleanSupplier cancelled) throws Exception {
                    assertEquals(expected, conflictScope(request));
                    assertNotNull(locks.findOwned(runId, expected));
                    return super.fetch(request, consumer, cancelled);
                }
            };
            assertEquals(SyncRunState.VERIFIED, runner.run(runId, null, "target", frozen, adapter, () -> false).state());
            assertNull(locks.findOwned(runId, expected));
        }
    }
    @Test void narrowedConflictScopeFailsDurablyBeforeFetchingOrSending() throws Exception {
        Path path = root.resolve("ledger.sqlite3");
        var ledger = new SyncRunLedger(path); var locks = new DatasetIntervalLock(path);
        var runner = new SyncJobRunner<Row,String>(ledger, locks);
        LocalDate from = LocalDate.of(2026, 9, 27), to = from.plusDays(2);
        var adapter = new Adapter() {
            @Override public DatasetIntervalLock.Scope conflictScope(FrozenRequest request) {
                return new DatasetIntervalLock.Scope("test_dataset", from.plusDays(1), to);
            }
        };
        var result = runner.run("narrowed", null, "target", request(Duration.ofMinutes(1), from, to), adapter, () -> false);
        assertEquals(SyncRunState.FAILED, result.state());
        assertEquals("IllegalArgumentException", result.errorCode());
        assertEquals(SyncRunState.FAILED, ledger.get("narrowed").state());
        assertEquals(1, ledger.entries("narrowed", null, 10).size());
        assertEquals(0, adapter.fetches); assertEquals(0, adapter.sends);
        assertNull(locks.findOwned("narrowed", adapter.conflictScope(request())));
    }
    @Test void differentDatasetOrBoundedScopeForUnboundedRequestIsRejectedBeforeLocking() throws Exception {
        Path path = root.resolve("ledger.sqlite3");
        var ledger = new SyncRunLedger(path); var locks = new DatasetIntervalLock(path);
        var runner = new SyncJobRunner<Row,String>(ledger, locks);
        LocalDate date = LocalDate.of(2026, 9, 29);
        int index = 0;
        for (var invalid : List.of(DatasetIntervalLock.Scope.allDates("other_dataset"),
                new DatasetIntervalLock.Scope("test_dataset", date, date))) {
            String runId = "invalid-scope-" + index++;
            var adapter = new Adapter() {
                @Override public DatasetIntervalLock.Scope conflictScope(FrozenRequest request) { return invalid; }
            };
            var result = runner.run(runId, null, "target", request(), adapter, () -> false);
            assertEquals(SyncRunState.FAILED, result.state());
            assertEquals("IllegalArgumentException", result.errorCode());
            assertEquals(SyncRunState.FAILED, ledger.get(runId).state());
            assertEquals(0, adapter.fetches); assertEquals(0, adapter.sends);
            assertNull(locks.findOwned(runId, invalid));
        }
    }
    @Test void sourceFailureIsFailedAndPreflightCancellationNeverFetches() throws Exception {
        Path path = root.resolve("ledger.sqlite3"); var ledger = new SyncRunLedger(path);
        var runner = new SyncJobRunner<Row,String>(ledger, new DatasetIntervalLock(path));
        var adapter = new Adapter(); adapter.failSource = true;
        assertEquals(SyncRunState.FAILED, runner.run("failure", null, "target", request(), adapter, () -> false).state());
        var cancelled = new Adapter();
        assertEquals(SyncRunState.CANCELLED, runner.run("cancel", null, "target", request(), cancelled, () -> true).state());
        assertEquals(0, cancelled.fetches); assertEquals(0, cancelled.sends);
    }
    @Test void unknownWritePastDeadlineRetainsExclusionAndRejectsReplay() throws Exception {
        Path path=root.resolve("ledger.sqlite3"); var ledger=new SyncRunLedger(path);
        var runner=new SyncJobRunner<Row,String>(ledger,new DatasetIntervalLock(path));
        var uncertain=new Adapter() {
            @Override public void send(List<Row> rows) {
                super.send(rows);
                throw new IllegalStateException("Transport result unknown");
            }
        };
        var result=runner.run("uncertain",null,"target",request(Duration.ofSeconds(1)),uncertain,()->false);
        assertEquals(SyncRunState.IN_DOUBT,result.state());
        assertEquals(1,uncertain.sends);
        assertEquals(SyncRunState.IN_DOUBT,SyncRunLedger.openReadOnly(path).get("uncertain").state());
        var replay=new Adapter();
        var blocked=runner.run("replay",null,"target",request(),replay,()->false);
        assertEquals("DATASET_INTERVAL_BUSY",blocked.errorCode());
        assertEquals(0,replay.fetches); assertEquals(0,replay.sends);
    }
    @Test void adapterFailureAfterFetchClosesCurrentSliceWithoutSending() throws Exception {
        Path path=root.resolve("ledger.sqlite3"); var ledger=new SyncRunLedger(path);
        var runner=new SyncJobRunner<Row,String>(ledger,new DatasetIntervalLock(path));
        var broken=new Adapter() {
            @Override public VerifiedBatchExecutor.Port<Row,String> port() { throw new IllegalStateException("Adapter unavailable"); }
        };
        assertEquals(SyncRunState.FAILED,runner.run("broken",null,"target",request(),broken,()->false).state());
        assertEquals(0,broken.sends);
        assertTrue(ledger.entries("broken",null,10).stream().allMatch(e->e.state()==SyncRunState.FAILED));
    }
    @Test void durableCancellationAfterFirstPageStopsSecondSendAndPreservesReceipt() throws Exception {
        Path path=root.resolve("ledger.sqlite3"); var ledger=new SyncRunLedger(path);
        var runner=new SyncJobRunner<Row,String>(ledger,new DatasetIntervalLock(path));
        var adapter=new Adapter() {
            @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,
                    SyncJobRunner.PageConsumer<Row> consumer,BooleanSupplier cancelled) throws Exception {
                consumer.accept(new SyncJobRunner.Page<>(List.of(new Row("a","1")),"source-a","response-a",null));
                assertTrue(new SyncRunLedger(path).requestCancellation("cancel-between-pages"));
                consumer.accept(new SyncJobRunner.Page<>(List.of(new Row("b","2")),"source-b","response-b",null));
                return new SyncJobRunner.SourceCompletion(2,2,true,"complete");
            }
        };
        var result=runner.run("cancel-between-pages",null,"target",request(),adapter,()->false);
        assertEquals(SyncRunState.CANCELLED,result.state());
        assertEquals(1,adapter.sends); assertEquals(1,result.verifiedRows());
        assertEquals(1,ledger.entries(result.runId(),null,10).stream()
                .filter(e->e.kind()==SyncRunLedger.Kind.SLICE && e.state()==SyncRunState.VERIFIED).count());
        assertTrue(SyncRunLedger.openReadOnly(path).cancellationRequested(result.runId()));
    }
    static class RecoveringAdapter extends Adapter {
        boolean failAfterFirst=true;
        boolean revised;
        @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,
                SyncJobRunner.PageConsumer<Row> consumer,BooleanSupplier cancelled) throws Exception {
            fetches++;
            consumer.accept(new SyncJobRunner.Page<>(List.of(new Row("a",revised?"revised":"1")),
                    revised?"source-a-revised":"source-a","response-a",null));
            if(failAfterFirst) throw new IllegalStateException("Second page source failure");
            consumer.accept(new SyncJobRunner.Page<>(List.of(new Row("b","2")),"source-b","response-b",null));
            return new SyncJobRunner.SourceCompletion(2,2,true,"complete");
        }
    }
    @Test void resumeRefetchesButDoesNotRewriteVerifiedPage() throws Exception {
        Path path=root.resolve("ledger.sqlite3"); var ledger=new SyncRunLedger(path);
        var adapter=new RecoveringAdapter();
        var runner=new SyncJobRunner<Row,String>(ledger,new DatasetIntervalLock(path));
        assertEquals(SyncRunState.PARTIAL,runner.run("first",null,"target",request(),adapter,()->false).state());
        adapter.failAfterFirst=false; adapter.sends=0;
        var reopened=new SyncRunLedger(path);
        var resumed=new SyncJobRunner<Row,String>(reopened,new DatasetIntervalLock(path))
                .resume("second","first","target",request(),adapter,()->false);
        assertEquals(SyncRunState.VERIFIED,resumed.state());
        assertEquals(1,resumed.reusedRows()); assertEquals(1,adapter.sends);
        assertEquals(2,resumed.verifiedRows());
        assertEquals(SyncRunState.PARTIAL,reopened.get("first").state());
        assertEquals("first",reopened.getRun("second").parentRunId());
    }
    @Test void changedSourceCannotReuseOldFingerprintAndTargetDriftStopsRecovery() throws Exception {
        Path path=root.resolve("ledger.sqlite3"); var ledger=new SyncRunLedger(path);
        var adapter=new RecoveringAdapter(); var runner=new SyncJobRunner<Row,String>(ledger,new DatasetIntervalLock(path));
        runner.run("first",null,"target",request(),adapter,()->false);
        adapter.failAfterFirst=false; adapter.stored.put("a",new Row("a","target-drift")); adapter.sends=0;
        assertEquals(SyncRunState.FAILED,runner.resume("drift","first","target",request(),adapter,()->false).state());
        assertEquals(0,adapter.sends);
        adapter.revised=true;
        var refreshed=runner.resume("refresh","first","target",request(),adapter,()->false);
        assertEquals(SyncRunState.VERIFIED,refreshed.state()); assertEquals(0,refreshed.reusedRows());
        assertEquals(2,adapter.sends);
    }
    @Test void changedTargetOrUnfinishedPriorRunCannotResume() throws Exception {
        Path path=root.resolve("ledger.sqlite3"); var ledger=new SyncRunLedger(path);
        var adapter=new RecoveringAdapter(); var runner=new SyncJobRunner<Row,String>(ledger,new DatasetIntervalLock(path));
        runner.run("first",null,"target",request(),adapter,()->false);
        assertThrows(IllegalArgumentException.class,()->runner.resume("bad-target","first","other",request(),adapter,()->false));
        ledger.createRun("unfinished",null,"target",request());
        assertThrows(IllegalStateException.class,()->runner.resume("bad-active","unfinished","target",request(),adapter,()->false));
    }
}
