package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** First writes and checkpoint recovery must use the same dataset value comparison. */
class VerifiedSliceRecoveryTest {
    @TempDir Path root;

    private static final SyncJobRunner.Page<SyncJobRunnerTest.Row> PAGE = new SyncJobRunner.Page<>(
            List.of(new SyncJobRunnerTest.Row("a", "1")), "source-a", "source-evidence", null);

    private static class Adapter extends SyncJobRunnerTest.Adapter {
        final boolean tolerant;
        String storedValue;
        Adapter(boolean tolerant, String storedValue) {
            this.tolerant = tolerant;
            this.storedValue = storedValue;
        }
        @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
                SyncJobRunner.PageConsumer<SyncJobRunnerTest.Row> consumer, BooleanSupplier cancelled) throws Exception {
            fetches++;
            consumer.accept(PAGE);
            return new SyncJobRunner.SourceCompletion(1, 1, true, "source-complete");
        }
        @Override public void send(List<SyncJobRunnerTest.Row> rows) {
            sends++;
            rows.forEach(row -> stored.put(row.key(), new SyncJobRunnerTest.Row(row.key(), storedValue)));
        }
        @Override public VerifiedBatchExecutor.Codec<SyncJobRunnerTest.Row, String> codec() {
            var exact = super.codec();
            if (!tolerant) return exact;
            return new VerifiedBatchExecutor.Codec<>() {
                public String key(SyncJobRunnerTest.Row row) { return exact.key(row); }
                public byte[] canonicalBytes(SyncJobRunnerTest.Row row) { return exact.canonicalBytes(row); }
                @Override public boolean equivalent(SyncJobRunnerTest.Row expected, SyncJobRunnerTest.Row actual) {
                    double left = Double.parseDouble(expected.value()), right = Double.parseDouble(actual.value());
                    return Objects.equals(expected.key(), actual.key()) && Double.isFinite(left)
                            && Double.isFinite(right) && Math.abs(left - right) <= 1e-8;
                }
            };
        }
    }

    @Test void defaultExactComparisonRejectsRoundingDriftDuringRecovery() throws Exception {
        var path = root.resolve("exact.sqlite3");
        var ledger = new SyncRunLedger(path);
        var request = new SyncJobRunnerTest().request();
        var adapter = new Adapter(false, "1");
        var runner = new SyncJobRunner<SyncJobRunnerTest.Row, String>(ledger, new DatasetIntervalLock(path));
        assertEquals(SyncRunState.VERIFIED, runner.run("original", null, "target", request, adapter, () -> false).state());
        adapter.stored.put("a", new SyncJobRunnerTest.Row("a", "1.000000001"));
        var recovery = VerifiedSliceRecovery.load(ledger, "original", request, "target");
        assertThrows(IllegalStateException.class, () -> recovery.revalidate(PAGE, adapter.codec(), adapter.port()));
        assertEquals(1, adapter.sends);
        assertEquals(SyncRunState.VERIFIED, ledger.get("original").state());
    }

    @Test void optInToleranceVerifiesFirstWriteAndReusesTheSameRoundedValuesWithoutResending() throws Exception {
        var path = root.resolve("tolerant.sqlite3");
        var ledger = new SyncRunLedger(path);
        var request = new SyncJobRunnerTest().request();
        var adapter = new Adapter(true, "1.000000001");
        var runner = new SyncJobRunner<SyncJobRunnerTest.Row, String>(ledger, new DatasetIntervalLock(path));
        assertEquals(SyncRunState.VERIFIED, runner.run("original", null, "target", request, adapter, () -> false).state());
        var recovery = VerifiedSliceRecovery.load(ledger, "original", request, "target");
        var readback = recovery.revalidate(PAGE, adapter.codec(), adapter.port());
        assertNotNull(readback);
        byte[] source = adapter.codec().canonicalBytes(PAGE.rows().getFirst());
        var hash = MessageDigest.getInstance("SHA-256");
        hash.update(ByteBuffer.allocate(4).putInt(source.length).array());
        hash.update(source);
        assertEquals(HexFormat.of().formatHex(hash.digest()), readback.valueDigest(),
                "Recovery keeps the canonical source digest while the dataset permits numeric tolerance");
        var resumed = runner.resume("resumed", "original", "target", request, adapter, () -> false);
        assertEquals(SyncRunState.VERIFIED, resumed.state());
        assertEquals(1, resumed.reusedRows());
        assertEquals(1, resumed.verifiedRows());
        assertEquals(1, adapter.sends, "A matching checkpoint must not replay the write");
    }

    @Test void optInToleranceRejectsOutOfToleranceRecoveryBeforeAnyReplay() throws Exception {
        var path = root.resolve("drift.sqlite3");
        var ledger = new SyncRunLedger(path);
        var request = new SyncJobRunnerTest().request();
        var adapter = new Adapter(true, "1.000000001");
        var runner = new SyncJobRunner<SyncJobRunnerTest.Row, String>(ledger, new DatasetIntervalLock(path));
        assertEquals(SyncRunState.VERIFIED, runner.run("original", null, "target", request, adapter, () -> false).state());
        adapter.stored.put("a", new SyncJobRunnerTest.Row("a", "1.001"));
        var recovery = VerifiedSliceRecovery.load(ledger, "original", request, "target");
        assertThrows(IllegalStateException.class, () -> recovery.revalidate(PAGE, adapter.codec(), adapter.port()));
        var resumed = runner.resume("resumed", "original", "target", request, adapter, () -> false);
        assertEquals(SyncRunState.FAILED, resumed.state());
        assertEquals(0, resumed.reusedRows());
        assertEquals(1, adapter.sends);
        assertEquals(SyncRunState.VERIFIED, ledger.get("original").state());
    }
}