package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedRunRecoveryTest {
    @TempDir Path root;
    static class Adapter extends SyncJobRunnerTest.Adapter {
        boolean sourceChanged;
        public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
                SyncJobRunner.PageConsumer<SyncJobRunnerTest.Row> consumer, BooleanSupplier cancelled) throws Exception {
            fetches++;
            consumer.accept(new SyncJobRunner.Page<>(List.of(new SyncJobRunnerTest.Row("a", "1")),
                    sourceChanged ? "new-source" : "source-a", "source-evidence", null));
            return new SyncJobRunner.SourceCompletion(1, 1, true, "source-complete");
        }
    }
    @Test void currentReadbackProducesReceiptWithoutWritingAndRejectsDrift() throws Exception {
        Path path = root.resolve("ledger.sqlite3");
        var ledger = new SyncRunLedger(path);
        var adapter = new Adapter();
        var request = new SyncJobRunnerTest().request();
        var result = new SyncJobRunner<SyncJobRunnerTest.Row,String>(ledger, new DatasetIntervalLock(path))
                .run("original", null, "target", request, adapter, () -> false);
        assertEquals(SyncRunState.VERIFIED, result.state());
        assertEquals(1, adapter.sends);
        Path receipt = Path.of(VerifiedRunRecovery.revalidate(new SyncRunLedger(path), "original", "target",
                request, adapter, () -> false, root.resolve("evidence")));
        assertTrue(Files.readString(receipt).contains("source-a"));
        assertEquals(1, adapter.sends, "Revalidation must never call the writer");
        adapter.stored.put("a", new SyncJobRunnerTest.Row("a", "changed"));
        assertThrows(IllegalStateException.class, () -> VerifiedRunRecovery.revalidate(ledger, "original", "target",
                request, adapter, () -> false, root.resolve("evidence")));
        adapter.stored.put("a", new SyncJobRunnerTest.Row("a", "1"));
        adapter.sourceChanged = true;
        assertThrows(IllegalStateException.class, () -> VerifiedRunRecovery.revalidate(ledger, "original", "target",
                request, adapter, () -> false, root.resolve("evidence")));
        assertEquals(1, adapter.sends);
        assertEquals(SyncRunState.VERIFIED, ledger.get("original").state(), "Original evidence remains immutable");
    }
}
