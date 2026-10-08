package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static com.zoutrankil.data.domain.SyncJobDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncJobRunnerBoundaryTest {
    @TempDir Path root;
    private SyncJobDefinition.FrozenRequest request(Duration timeout) {
        return new SyncJobDefinition("test.job", 1, "test_dataset", 1, "test_adapter",
                Set.of(Mode.SNAPSHOT), Mode.SNAPSHOT, Map.of(), "rate", "slice", "verify",
                new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(10)), timeout,
                new Budget(1, 1, 1, 1, 4096), 0, List.of(), Frequency.MANUAL,
                ZoneOffset.UTC, true, false).freeze(null, Map.of(), null, null,
                LocalDate.of(2020, 1, 2));
    }
    private record Fixture(SyncRunLedger ledger, DatasetIntervalLock locks,
                           SyncJobRunner<String, String> runner) {}
    private Fixture fixture() throws Exception {
        Path path = root.resolve("ledger.sqlite3");
        var ledger = new SyncRunLedger(path);
        var locks = new DatasetIntervalLock(path);
        return new Fixture(ledger, locks, new SyncJobRunner<>(ledger, locks));
    }
    private static final VerifiedBatchExecutor.Codec<String, String> CODEC = new VerifiedBatchExecutor.Codec<>() {
        public String key(String row) { return row; }
        public byte[] canonicalBytes(String row) { return row.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    };

    @Test void unknownSendAfterTimeoutCannotReleaseConflict() throws Exception {
        var f = fixture();
        var port = new VerifiedBatchExecutor.Port<String, String>() {
            public void preflight() {}
            public void send(List<String> rows) throws Exception { throw new TimeoutException("ACK unknown"); }
            public List<String> readback(List<String> keys) { return List.of(); }
            public boolean walSettled() { return true; }
        };
        var adapter = new SyncJobRunner.Adapter<String, String>() {
            public void preflight(SyncJobDefinition.FrozenRequest request) {}
            public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
                    SyncJobRunner.PageConsumer<String> consumer, BooleanSupplier cancelled) throws Exception {
                consumer.accept(new SyncJobRunner.Page<>(List.of("a"), "source-sha", "response.json", "cursor"));
                return new SyncJobRunner.SourceCompletion(1, 1, true, "response.json");
            }
            public VerifiedBatchExecutor.Codec<String, String> codec() { return CODEC; }
            public VerifiedBatchExecutor.Port<String, String> port() { return port; }
        };
        var result = f.runner.run("run-unknown", null, "isolated", request(Duration.ofMillis(300)),
                adapter, () -> false);
        assertEquals(SyncRunState.IN_DOUBT, result.state());
        assertEquals(SyncRunState.IN_DOUBT, f.ledger.get("run-unknown").state());
        assertNull(f.locks.acquire("run-unknown", DatasetIntervalLock.Scope.allDates("test_dataset")));
    }

    @Test void emptyPageBeforeSourceFailureIsNeverVerifiedEmpty() throws Exception {
        var f = fixture();
        var adapter = new SyncJobRunner.Adapter<String, String>() {
            public void preflight(SyncJobDefinition.FrozenRequest request) {}
            public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
                    SyncJobRunner.PageConsumer<String> consumer, BooleanSupplier cancelled) throws Exception {
                consumer.accept(new SyncJobRunner.Page<>(List.of(), "source-sha", "response.json", null));
                throw new IllegalStateException("source failed after empty page");
            }
            public VerifiedBatchExecutor.Codec<String, String> codec() { return CODEC; }
            public VerifiedBatchExecutor.Port<String, String> port() { fail(); return null; }
        };
        var result = f.runner.run("run-empty-error", null, "isolated", request(Duration.ofSeconds(2)),
                adapter, () -> false);
        assertEquals(SyncRunState.FAILED, result.state());
        var slice = f.ledger.entries("run-empty-error", null, 10).stream()
                .filter(entry -> entry.kind() == SyncRunLedger.Kind.SLICE).findFirst().orElseThrow();
        assertEquals(SyncRunState.FAILED, slice.state());
    }
}
