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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static com.zoutrankil.data.domain.SyncJobDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncJobRunnerKeyBoundaryTest {
    @TempDir Path root;

    @Test void repeatedBusinessKeyOnLaterPageStopsBeforeSecondWrite() throws Exception {
        var definition = new SyncJobDefinition("test.job", 1, "test_dataset", 1, "test_adapter",
                Set.of(Mode.SNAPSHOT), Mode.SNAPSHOT, Map.of(), "rate", "slice", "verify",
                new RetryPolicy(1, Duration.ofMillis(1), Duration.ofSeconds(1)), Duration.ofSeconds(5),
                new Budget(1, 2, 2, 2, 4096), 0, List.of(), Frequency.MANUAL,
                ZoneOffset.UTC, true, false);
        var request = definition.freeze(null, Map.of(), null, null, LocalDate.of(2026, 9, 29));
        var ledger = new SyncRunLedger(root.resolve("ledger.sqlite3"));
        var stored = new ArrayList<String>();
        var codec = new VerifiedBatchExecutor.Codec<String, String>() {
            public String key(String row) { return row; }
            public byte[] canonicalBytes(String row) { return row.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
        };
        var port = new VerifiedBatchExecutor.Port<String, String>() {
            public void preflight() {}
            public void send(List<String> rows) { stored.addAll(rows); }
            public List<String> readback(List<String> keys) { return stored.stream().filter(keys::contains).toList(); }
            public boolean walSettled() { return true; }
        };
        var adapter = new SyncJobRunner.Adapter<String, String>() {
            public void preflight(SyncJobDefinition.FrozenRequest ignored) {}
            public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest ignored,
                    SyncJobRunner.PageConsumer<String> consumer, BooleanSupplier cancelled) throws Exception {
                consumer.accept(new SyncJobRunner.Page<>(List.of("a"), "sha-1", "page-1", null));
                consumer.accept(new SyncJobRunner.Page<>(List.of("a"), "sha-2", "page-2", null));
                return new SyncJobRunner.SourceCompletion(2, 2, true, "complete");
            }
            public VerifiedBatchExecutor.Codec<String, String> codec() { return codec; }
            public VerifiedBatchExecutor.Port<String, String> port() { return port; }
        };
        var result = new SyncJobRunner<String, String>(ledger, new DatasetIntervalLock(root.resolve("ledger.sqlite3")))
                .run("duplicate-run", null, "isolated", request, adapter, () -> false);
        assertEquals(SyncRunState.PARTIAL, result.state());
        assertEquals(List.of("a"), stored);
        assertEquals(1, result.verifiedRows());
    }
}
