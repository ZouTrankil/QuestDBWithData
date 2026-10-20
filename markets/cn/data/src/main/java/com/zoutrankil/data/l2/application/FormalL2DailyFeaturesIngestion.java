package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.domain.IntervalLockStore;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesKey;
import com.zoutrankil.data.l2.domain.FormalL2Frontier;
import com.zoutrankil.data.l2.domain.L2DailyFeaturesRows;
import com.zoutrankil.data.l2.port.FormalL2FrontierReader;
import com.zoutrankil.data.l2.port.FormalL2IntervalAuthority;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Formal daily L2 owner: validates existing values and fills only source-certified missing keys. */
public final class FormalL2DailyFeaturesIngestion {
    public static final int MAX_DAY_ROWS = 10_000;
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final Duration NATIVE_JSONL_TIMEOUT = Duration.ofHours(2);
    private static final String TABLE = "l2_daily_features";

    public record Evidence(
            LocalDate day,
            String sourceFingerprint,
            String schemaFingerprint,
            String rootIdentity,
            int sourceRows,
            int selectedRows,
            int insertedRows,
            long verifiedFields,
            String targetIdentity,
            String targetVersion,
            String readbackDigest,
            String readerVersion) {}

    public record Reconciliation(
            LocalDate day,
            String priorRunId,
            int expectedRows,
            int exactExistingRows,
            int missingRows,
            String sourceFingerprint,
            String targetIdentity,
            String targetVersion,
            boolean leaseReleased) {}

    private final L2DailyFeaturesParquetSource source;
    private final L2DailyFeaturesWriteSession writer;
    private final FormalL2FrontierReader frontier;
    private final IntervalLockStore locks;

    public FormalL2DailyFeaturesIngestion(
            L2DailyFeaturesParquetSource source,
            L2DailyFeaturesWriteSession writer,
            FormalL2FrontierReader frontier,
            IntervalLockStore locks) {
        this.source = Objects.requireNonNull(source);
        this.writer = Objects.requireNonNull(writer);
        this.frontier = Objects.requireNonNull(frontier);
        this.locks = Objects.requireNonNull(locks);
        if (!TABLE.equals(writer.tableName()))
            throw new IllegalArgumentException("Fixed formal L2 table required");
    }

    /** Pure read validation. A table row or old receipt cannot replace a currently available certified source. */
    public Evidence validate(LocalDate day) throws Exception {
        return validate(day, () -> false);
    }

    public Evidence validate(LocalDate day, BooleanSupplier cancelled) throws Exception {
        var control = NativeL2DailyParquetReader.Control.of(cancelled, TIMEOUT);
        var inspection = inspect(day, cancelled);
        var expected = load(inspection, cancelled);
        writer.preflight();
        var before = frontier.read();
        var missing = compare(expected, control);
        if (!missing.isEmpty())
            throw new IOException(
                    "Formal L2 is missing " + missing.size() + " source-certified business keys");
        if (!before.equals(frontier.read()))
            throw new IOException("Formal L2 changed during bounded validation");
        if (!inspection.equals(inspect(day, cancelled)))
            throw new IOException("Certified L2 source changed during validation");
        if (!before.equals(frontier.read()))
            throw new IOException("Formal L2 changed during source stability verification");
        return evidence(day, inspection, expected, 0, before);
    }

    public Evidence ingestAndValidate(LocalDate day, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(day);
        Objects.requireNonNull(cancelled);
        var scope = new IntervalLockStore.Scope(TABLE, day, day);
        var lease = locks.acquire("native-l2-" + UUID.randomUUID(), scope);
        if (lease == null)
            throw new IllegalStateException("Formal L2 date is owned by another writer");
        boolean sendStarted = false, verified = false;
        Evidence result = null;
        try {
            var control = NativeL2DailyParquetReader.Control.of(cancelled, TIMEOUT);
            var inspection = inspect(day, cancelled);
            var expected = load(inspection, cancelled);
            writer.preflight();
            var original = frontier.read();
            var missing = compare(expected, control);
            if (!original.equals(frontier.read()))
                throw new IOException("Formal L2 changed during gap inspection");
            if (!inspection.equals(inspect(day, cancelled)))
                throw new IOException("Certified L2 source changed before submission");
            for (var batch : boundedBatches(missing)) {
                control.run();
                // A fresh read prevents a previously missing key being treated as a rewrite
                // request.
                if (!writer.readback(batch.stream().map(L2DailyFeatures::key).toList()).isEmpty())
                    throw new IOException(
                            "Formal L2 gap changed before source-certified submission");
                sendStarted = true;
                writer.send(batch);
            }
            if (sendStarted) awaitWalSettled(control);
            var finalFrontier = frontier.read();
            if (!compare(expected, control).isEmpty())
                throw new IOException("Formal L2 gap remains after bounded ingestion");
            if (!finalFrontier.equals(frontier.read()))
                throw new IOException("Formal L2 changed during final verification");
            if (!inspection.equals(inspect(day, cancelled)))
                throw new IOException("Certified L2 source changed after ingestion");
            if (!finalFrontier.equals(frontier.read()))
                throw new IOException(
                        "Formal L2 changed during final source stability verification");
            result = evidence(day, inspection, expected, missing.size(), finalFrontier);
            verified = true;
            return result;
        } finally {
            if (locks instanceof FormalL2IntervalAuthority persistent)
                persistent.finish(
                        lease, result == null ? null : ledgerEvidence(result), sendStarted);
            if (verified || !sendStarted) locks.releaseVerified(lease);
            else locks.retainInDoubt(lease);
        }
    }

    /** Publish rows emitted by the native archive parser after verifying its daily manifest and aggregate hash. */
    public Evidence ingestNativeJsonlAndValidate(
            LocalDate day, Path outputRoot, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(day);
        Objects.requireNonNull(outputRoot);
        Objects.requireNonNull(cancelled);
        var sourceDay = L2NativeDailyFeaturesJsonlSource.inspect(outputRoot, day);
        var expected = sourceDay.rows();
        if (expected.size() > MAX_DAY_ROWS)
            throw new IOException("Formal L2 day exceeds its row bound");

        var scope = new IntervalLockStore.Scope(TABLE, day, day);
        var runId = "native-l2-jsonl-" + UUID.randomUUID();
        var lease = acquire(runId, scope, L2NativeDailyFeaturesJsonlSource.READER_VERSION);
        if (lease == null)
            throw new IllegalStateException("Formal L2 date is owned by another writer");
        boolean sendStarted = false, verified = false;
        Evidence result = null;
        try {
            var control = NativeL2DailyParquetReader.Control.of(cancelled, NATIVE_JSONL_TIMEOUT);
            sourceDay.assertUnchanged();
            writer.preflight();
            var original = frontier.read();
            var missing = compare(expected, control);
            if (!original.equals(frontier.read()))
                throw new IOException("Formal L2 changed during gap inspection");
            sourceDay.assertUnchanged();
            for (var batch : boundedBatches(missing)) {
                control.run();
                if (!writer.readback(batch.stream().map(L2DailyFeatures::key).toList()).isEmpty())
                    throw new IOException(
                            "Formal L2 gap changed before source-certified submission");
                // Rows are already parsed and receipt-hash checked; verify the full day after
                // delivery,
                // not once per bounded batch.
                sendStarted = true;
                writer.send(batch);
            }
            if (sendStarted) awaitWalSettled(control);
            var finalFrontier = frontier.read();
            if (!compare(expected, control).isEmpty())
                throw new IOException("Formal L2 gap remains after bounded ingestion");
            if (!finalFrontier.equals(frontier.read()))
                throw new IOException("Formal L2 changed during final verification");
            sourceDay.assertUnchanged();
            if (!finalFrontier.equals(frontier.read()))
                throw new IOException(
                        "Formal L2 changed during final source stability verification");
            result =
                    evidence(
                            day,
                            sourceDay.sourceFingerprint(),
                            sourceDay.schemaFingerprint(),
                            sourceDay.rootIdentity(),
                            expected.size(),
                            expected,
                            missing.size(),
                            finalFrontier,
                            L2NativeDailyFeaturesJsonlSource.READER_VERSION);
            verified = true;
            return result;
        } finally {
            if (locks instanceof FormalL2IntervalAuthority persistent)
                persistent.finish(
                        lease, result == null ? null : ledgerEvidence(result), sendStarted);
            if (verified || !sendStarted) locks.releaseVerified(lease);
            else locks.retainInDoubt(lease);
        }
    }

    /** Exact-readback recovery for an interrupted native JSONL submission; this method never writes QuestDB rows. */
    public Reconciliation reconcileNativeJsonlInDoubt(
            LocalDate day, Path outputRoot, String priorRunId, BooleanSupplier cancelled)
            throws Exception {
        Objects.requireNonNull(day);
        Objects.requireNonNull(outputRoot);
        Objects.requireNonNull(priorRunId);
        Objects.requireNonNull(cancelled);
        if (!(locks instanceof FormalL2IntervalAuthority authority))
            throw new IllegalStateException(
                    "Persistent formal L2 interval authority is required for recovery");
        var scope = new IntervalLockStore.Scope(TABLE, day, day);
        var lease = authority.findOwned(priorRunId, scope);
        if (lease == null || !lease.inDoubt())
            throw new IllegalStateException(
                    "Exact-readback recovery requires the prior run's in-doubt date lease");

        var sourceDay = L2NativeDailyFeaturesJsonlSource.inspect(outputRoot, day);
        var expected = sourceDay.rows();
        if (expected.size() > MAX_DAY_ROWS)
            throw new IOException("Formal L2 day exceeds its row bound");
        var control = NativeL2DailyParquetReader.Control.of(cancelled, NATIVE_JSONL_TIMEOUT);
        writer.preflight();
        var original = frontier.read();
        var missing = compare(expected, control);
        if (!original.equals(frontier.read()))
            throw new IOException("Formal L2 changed during interrupted-write reconciliation");
        sourceDay.assertUnchanged();
        if (!writer.uncertainSenderStopped())
            throw new IOException("Formal L2 sender is still active during reconciliation");

        authority.releaseAfterReconciliation(lease, true, true);
        if (authority.findOwned(priorRunId, scope) != null)
            throw new IOException("Formal L2 in-doubt lease remains after exact-readback release");
        var reconciled = frontier.read();
        if (!original.equals(reconciled))
            throw new IOException("Formal L2 changed while releasing the reconciled lease");
        sourceDay.assertUnchanged();
        return new Reconciliation(
                day,
                priorRunId,
                expected.size(),
                expected.size() - missing.size(),
                missing.size(),
                sourceDay.sourceFingerprint(),
                reconciled.identity(),
                reconciled.version(),
                true);
    }

    private IntervalLockStore.Lease acquire(
            String runId, IntervalLockStore.Scope scope, String readerVersion) {
        if (locks instanceof FormalL2IntervalAuthority authority)
            return authority.acquire(runId, scope, readerVersion);
        return locks.acquire(runId, scope);
    }

    private List<List<L2DailyFeatures>> boundedBatches(List<L2DailyFeatures> rows) {
        var result = new ArrayList<List<L2DailyFeatures>>();
        var batch = new ArrayList<L2DailyFeatures>();
        long bytes = 0;
        for (var row : rows) {
            byte[] canonical = writer.codec().canonicalBytes(row);
            long size = writer.codec().estimatedTransportBytes(row, canonical);
            if (size < 0 || size > L2DailyFeaturesRows.MAX_FORMAL_BATCH_BYTES)
                throw new IllegalArgumentException(
                        "One formal L2 source row exceeds its transport byte budget");
            if (!batch.isEmpty()
                    && (batch.size() == L2DailyFeaturesRows.MAX_BATCH_ROWS
                            || size > L2DailyFeaturesRows.MAX_FORMAL_BATCH_BYTES - bytes)) {
                result.add(List.copyOf(batch));
                batch.clear();
                bytes = 0;
            }
            batch.add(row);
            bytes = Math.addExact(bytes, size);
        }
        if (!batch.isEmpty()) result.add(List.copyOf(batch));
        return List.copyOf(result);
    }

    private static Map<String, Object> ledgerEvidence(Evidence evidence) {
        return Map.ofEntries(
                Map.entry("day", evidence.day().toString()),
                Map.entry("sourceComplete", true),
                Map.entry("returnedRows", evidence.selectedRows()),
                Map.entry("submittedRows", evidence.insertedRows()),
                Map.entry("targetIdentity", evidence.targetIdentity()),
                Map.entry("targetVersion", evidence.targetVersion()),
                Map.entry("schemaFingerprint", evidence.schemaFingerprint()),
                Map.entry("rootIdentity", evidence.rootIdentity()),
                Map.entry("verifiedFields", evidence.verifiedFields()),
                Map.entry("readerVersion", evidence.readerVersion()),
                Map.entry(
                        "verification",
                        Map.of(
                                "passed",
                                true,
                                "expectedRows",
                                evidence.selectedRows(),
                                "actualRows",
                                evidence.selectedRows(),
                                "matchedRows",
                                evidence.selectedRows(),
                                "mismatchedRows",
                                0,
                                "duplicateKeys",
                                0,
                                "missingKeys",
                                0,
                                "sourceFingerprint",
                                evidence.sourceFingerprint(),
                                "readbackEvidence",
                                evidence.readbackDigest())));
    }

    private L2DailyFeaturesParquetSource.Inspection inspect(
            LocalDate day, BooleanSupplier cancelled) throws Exception {
        var inspection =
                source.inspect(day, day, List.of(), MAX_DAY_ROWS, 1000, cancelled, TIMEOUT);
        if (!inspection.dates().equals(List.of(day)) || !inspection.completeForSelectedSymbols())
            throw new IOException(
                    "Formal L2 requires one complete manifest-certified business date");
        return inspection;
    }

    private List<L2DailyFeatures> load(
            L2DailyFeaturesParquetSource.Inspection inspection, BooleanSupplier cancelled)
            throws Exception {
        var rows = new ArrayList<L2DailyFeatures>();
        var completion =
                source.stream(
                        inspection,
                        List.of(),
                        MAX_DAY_ROWS,
                        1000,
                        page -> rows.addAll(page.rows()),
                        cancelled,
                        TIMEOUT);
        if (!completion.complete()
                || completion.rows() != inspection.selectedRows()
                || rows.size() != completion.rows())
            throw new IOException("Formal L2 source completion is incomplete");
        return List.copyOf(rows);
    }

    private List<L2DailyFeatures> compare(
            List<L2DailyFeatures> expected, NativeL2DailyParquetReader.Control control)
            throws Exception {
        var missing = new ArrayList<L2DailyFeatures>();
        int present = 0;
        for (int offset = 0;
                offset < expected.size();
                offset += L2DailyFeaturesRows.MAX_READBACK_KEYS) {
            control.run();
            var batch =
                    expected.subList(
                            offset,
                            Math.min(
                                    expected.size(),
                                    offset + L2DailyFeaturesRows.MAX_READBACK_KEYS));
            var actual = read(batch);
            present += actual.size();
            for (var row : batch) {
                var stored = actual.get(row.key());
                if (stored == null) missing.add(row);
                else if (!Arrays.equals(
                        L2DailyFeaturesRows.canonicalBytes(row),
                        L2DailyFeaturesRows.canonicalBytes(stored)))
                    throw new IOException(
                            "Formal L2 existing values differ from certified source at "
                                    + row.key());
            }
        }
        if (writer.countRows(expected.getFirst().tradeDate()) != present)
            throw new IOException("Formal L2 date has duplicate or source-uncovered business keys");
        return missing;
    }

    private HashMap<L2DailyFeaturesKey, L2DailyFeatures> read(List<L2DailyFeatures> expected)
            throws Exception {
        var keys = expected.stream().map(L2DailyFeatures::key).toList();
        var actual = new HashMap<L2DailyFeaturesKey, L2DailyFeatures>();
        for (var row : writer.readback(keys)) {
            if (!keys.contains(row.key()) || actual.put(row.key(), row) != null)
                throw new IOException(
                        "Formal L2 readback returned duplicate or unexpected business keys");
        }
        return actual;
    }

    private void awaitWalSettled(NativeL2DailyParquetReader.Control control) throws Exception {
        while (!writer.walSettled()) {
            control.run();
            Thread.sleep(20);
        }
    }

    private static Evidence evidence(
            LocalDate day,
            L2DailyFeaturesParquetSource.Inspection inspection,
            List<L2DailyFeatures> rows,
            int inserted,
            FormalL2Frontier frontier)
            throws Exception {
        return evidence(
                day,
                inspection.sourceFingerprint(),
                inspection.schemaFingerprint(),
                inspection.rootIdentity(),
                inspection.sourceRows(),
                rows,
                inserted,
                frontier,
                "java-parquet-1.17.1");
    }

    private static Evidence evidence(
            LocalDate day,
            String sourceFingerprint,
            String schemaFingerprint,
            String rootIdentity,
            int sourceRows,
            List<L2DailyFeatures> rows,
            int inserted,
            FormalL2Frontier frontier,
            String readerVersion)
            throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        for (var row : rows) {
            byte[] bytes = L2DailyFeaturesRows.canonicalBytes(row);
            hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            hash.update(bytes);
        }
        return new Evidence(
                day,
                sourceFingerprint,
                schemaFingerprint,
                rootIdentity,
                sourceRows,
                rows.size(),
                inserted,
                Math.multiplyExact((long) rows.size(), 110L),
                frontier.identity(),
                frontier.version(),
                HexFormat.of().formatHex(hash.digest()),
                readerVersion);
    }
}
