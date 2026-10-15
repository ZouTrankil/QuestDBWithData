package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.domain.IntervalLockStore;
import com.zoutrankil.data.domain.L2DailyFeatureField;
import com.zoutrankil.data.domain.L2DailyFeatures;
import com.zoutrankil.data.domain.L2DailyFeaturesKey;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.data.l2.domain.FormalL2Frontier;
import com.zoutrankil.data.l2.storage.PersistentFormalL2IntervalAuthority;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.domain.SyncRunState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FormalL2DailyFeaturesIngestionTest {
    static final LocalDate DAY = LocalDate.of(2026, 9, 21);
    static final String HASH = "a".repeat(64);
    @TempDir Path temporary;

    @Test void fillsOnlyConfirmedMissingKeysAndPreservesUncertifiedOlderHistory() throws Exception {
        var expected = List.of(row(DAY, "000001.SZ", 1.25), row(DAY, "000002.SZ", null));
        var writer = new Writer();
        writer.rows.put(expected.getFirst().key(), expected.getFirst());
        var old = row(DAY.minusDays(100), "000001.SZ", 42.0); writer.rows.put(old.key(), old);
        var locks = new Locks();
        var service = service(expected, writer, locks);
        var evidence = service.ingestAndValidate(DAY, () -> false);
        assertEquals(List.of(expected.getLast()), writer.sent);
        assertEquals(old, writer.rows.get(old.key()));
        assertEquals(1, evidence.insertedRows()); assertEquals(220, evidence.verifiedFields());
        assertEquals(2, evidence.selectedRows()); assertTrue(locks.released); assertFalse(locks.inDoubt);
        assertEquals(0, service.validate(DAY).insertedRows()); assertEquals(1, writer.sent.size());
    }

    @Test void existingValueConflictBlocksBeforeAnySubmission() throws Exception {
        var expected = List.of(row(DAY, "000001.SZ", 1.25));
        var writer = new Writer(); var conflict = row(DAY, "000001.SZ", 2.0); writer.rows.put(conflict.key(), conflict);
        var locks = new Locks(); var service = service(expected, writer, locks);
        assertThrows(IOException.class, () -> service.ingestAndValidate(DAY, () -> false));
        assertTrue(writer.sent.isEmpty()); assertTrue(locks.released); assertEquals(conflict, writer.rows.get(conflict.key()));
    }

    @Test void orphanKeysMissingReadbackAndCancellationNeverCertifyOrOverwrite() throws Exception {
        var expected = List.of(row(DAY, "000001.SZ", 1.25));
        var writer = new Writer(); var locks = new Locks(); var service = service(expected, writer, locks);
        assertThrows(IOException.class, () -> service.validate(DAY));
        writer.rows.put(row(DAY, "000002.SZ", 2.0).key(), row(DAY, "000002.SZ", 2.0));
        assertThrows(IOException.class, () -> service.ingestAndValidate(DAY, () -> false));
        assertTrue(writer.sent.isEmpty()); writer.rows.clear();
        assertThrows(CancellationException.class, () -> service.ingestAndValidate(DAY, () -> true));
        assertTrue(writer.sent.isEmpty());
    }

    @Test void unknownDeliveryRetainsPersistentDateLockForReconciliation() throws Exception {
        var writer = new Writer(); writer.failSend = true; var locks = new Locks();
        var service = service(List.of(row(DAY, "000001.SZ", 1.25)), writer, locks);
        assertThrows(IOException.class, () -> service.ingestAndValidate(DAY, () -> false));
        assertTrue(locks.inDoubt); assertFalse(locks.released);
    }

    @Test void largeDailyGapSplitsOnTransportBytesBeforeTheRowCeiling() throws Exception {
        var expected = java.util.stream.IntStream.range(0, 200)
                .mapToObj(index -> row(DAY, "%06d.SZ".formatted(index), 1.25)).toList();
        var writer = new Writer();
        var evidence = service(expected, writer, new Locks()).ingestAndValidate(DAY, () -> false);
        assertEquals(200, evidence.insertedRows());
        assertEquals(expected, writer.sent);
        assertTrue(writer.txn > 1, "110 fields must obey the byte budget even below 200 rows");
    }

    @Test void realSqliteAuthorityIsLazySharesDateExclusionAndDurablyRetainsUnknownDelivery() throws Exception {
        Path file = temporary.resolve("formal.sqlite3");
        var first = new PersistentFormalL2IntervalAuthority(file);
        var second = new PersistentFormalL2IntervalAuthority(file);
        assertFalse(Files.exists(file));
        var scope = new IntervalLockStore.Scope("l2_daily_features", DAY, DAY);
        var lease = first.acquire("native-proof-one", scope);
        assertNotNull(lease);
        assertNull(second.acquire("native-proof-two", scope));
        first.finish(lease, null, true); first.retainInDoubt(lease);
        var ledger = SyncRunLedger.openReadOnly(file);
        assertEquals(SyncRunState.IN_DOUBT, ledger.get(lease.runId()).state());
        assertEquals(SyncRunState.FAILED, ledger.get("native-proof-two").state());
        assertTrue(second.findOwned(lease.runId(), scope).inDoubt());
        assertNull(second.acquire("native-proof-three", scope));
        assertThrows(IllegalStateException.class, () -> first.releaseVerified(lease));
        assertFalse(ledger.getRun(lease.runId()).frozenJson().contains("sourceFingerprint"),
                "An unknown run must not manufacture a source verification receipt");
    }

    private static FormalL2DailyFeaturesIngestion service(List<L2DailyFeatures> expected, Writer writer, Locks locks) throws Exception {
        var source = mock(L2DailyFeaturesParquetSource.class);
        var inspection = new L2DailyFeaturesParquetSource.Inspection(DAY, DAY, List.of(DAY), expected.size(), expected.size(),
                2, 1, 100, HASH, HASH, "l2-daily-features-parquet-v1", HASH, true);
        when(source.inspect(eq(DAY), eq(DAY), eq(List.of()), anyInt(), anyInt(), any(), any())).thenReturn(inspection);
        when(source.stream(eq(inspection), eq(List.of()), anyInt(), anyInt(), any(), any(), any())).thenAnswer(call -> {
            SyncJobRunner.PageConsumer<L2DailyFeatures> consumer = call.getArgument(4);
            consumer.accept(new SyncJobRunner.Page<>(expected, HASH, "{}", "page"));
            return new SyncJobRunner.SourceCompletion(1, expected.size(), true, "{}");
        });
        return new FormalL2DailyFeaturesIngestion(source, writer,
                () -> new FormalL2Frontier(HASH, writer.txn, writer.txn), locks);
    }
    private static L2DailyFeatures row(LocalDate date, String symbol, Double value) {
        var fields = new EnumMap<L2DailyFeatureField, Object>(L2DailyFeatureField.class);
        for (var field : L2DailyFeatureField.values()) if (!field.identity()) fields.put(field, null);
        fields.put(L2DailyFeatureField.MFI_SCORE, value);
        return new L2DailyFeatures(date, symbol, fields);
    }
    private static final class Writer implements L2DailyFeaturesWriteSession {
        final LinkedHashMap<L2DailyFeaturesKey, L2DailyFeatures> rows = new LinkedHashMap<>();
        final ArrayList<L2DailyFeatures> sent = new ArrayList<>();
        long txn; boolean failSend;
        @Override public String tableName() { return "l2_daily_features"; }
        @Override public VerifiedBatchExecutor.Codec<L2DailyFeatures, L2DailyFeaturesKey> codec() { return CODEC; }
        @Override public void preflight() {}
        @Override public void send(List<L2DailyFeatures> values) throws IOException {
            assertTrue(values.size() <= 200);
            assertTrue(values.stream().mapToLong(row -> CODEC.estimatedTransportBytes(row, CODEC.canonicalBytes(row))).sum() <= 1_048_576,
                    "Formal batch exceeded the actual write port byte budget");
            if (failSend) throw new IOException("unknown delivery");
            sent.addAll(values); values.forEach(row -> rows.put(row.key(), row)); txn++;
        }
        @Override public List<L2DailyFeatures> readback(List<L2DailyFeaturesKey> keys) {
            return keys.stream().filter(rows::containsKey).map(rows::get).toList();
        }
        @Override public int countRows(LocalDate date) { return (int) rows.keySet().stream().filter(key -> key.tradeDate().equals(date)).count(); }
        @Override public List<LocalDate> readExistingDates() { throw new AssertionError("No global formal-history scan permitted"); }
        @Override public boolean walSettled() { return true; }
        @Override public boolean uncertainSenderStopped() { return true; }
    }
    private static final class Locks implements IntervalLockStore {
        boolean released, inDoubt;
        @Override public Lease acquire(String runId, Scope scope) { return new Lease("lock", runId, scope, false); }
        @Override public Lease findOwned(String runId, Scope scope) { return null; }
        @Override public void retainInDoubt(Lease lease) { inDoubt = true; }
        @Override public void releaseVerified(Lease lease) { released = true; }
        @Override public void releaseAfterReconciliation(Lease lease, boolean writerStopped, boolean exactReadback) { throw new AssertionError(); }
    }
}
