package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.stock.domain.StockExecutionTables;

import com.zoutrankil.data.stock.port.StockStDailyTables;

import com.zoutrankil.data.stock.domain.StockStDailyState;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.stock.storage.StockStDailyPublicationStore;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;

/** D012-specific durable, serialized two-rename publication for bounded authoritative ST windows. */
public final class StockStDailyPublication {
    public enum State { PREPARED, OLD_MOVED, PUBLISHED, VERIFIED, IN_DOUBT, RESUMING }
    public enum Layout { ORIGINAL, OLD_MOVED, PUBLISHED, CONFLICT }
    public record Intent(String id, String runId, String logicalTarget, String frozenPhysicalTarget,
                         String target, String backup, String stage, StockStDailyState.Identity beforeIdentity,
                         StockStDailyState.Content before, StockStDailyState.Identity stageIdentity,
                         StockStDailyState.Content after, StockStDailyState.Content preservedOutside,
                         StockStDailyState.Content authoritativeWindow, LocalDate windowFrom, LocalDate windowTo,
                         int sourceRows, String sourceFingerprint, String stageReceipt, String stageReceiptFingerprint) {
        public Intent {
            Objects.requireNonNull(id); Objects.requireNonNull(runId); Objects.requireNonNull(logicalTarget);
            Objects.requireNonNull(frozenPhysicalTarget); Objects.requireNonNull(beforeIdentity);
            Objects.requireNonNull(before); Objects.requireNonNull(stageIdentity); Objects.requireNonNull(after);
            Objects.requireNonNull(preservedOutside); Objects.requireNonNull(authoritativeWindow);
            Objects.requireNonNull(windowFrom); Objects.requireNonNull(windowTo); Objects.requireNonNull(sourceFingerprint);
            Objects.requireNonNull(stageReceipt); Objects.requireNonNull(stageReceiptFingerprint);
            DatasetDefinition.identifier(target); DatasetDefinition.identifier(backup); DatasetDefinition.identifier(stage);
            if (Set.of(target, backup, stage).size() != 3 || windowFrom.isAfter(windowTo) || sourceRows < 0
                    || !sourceFingerprint.matches("[0-9a-f]{64}") || !stageReceiptFingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Complete bounded D012 publication intent required");
        }
    }
    public record Entry(Intent intent, State state, long revision) {}
    public record Result(Entry entry, Layout layout, StockStDailyState.Snapshot target,
                         StockStDailyState.Snapshot backup) {}
    public static final class Uncertain extends Exception {
        private final String runId;
        private final String publicationId;
        Uncertain(String runId, String publicationId, Exception cause) {
            super("D012 staged table publication needs reconciliation: " + runId, cause);
            this.runId = runId; this.publicationId = publicationId;
        }
        public String runId() { return runId; }
        public String publicationId() { return publicationId; }
    }

    private final StockStDailyTables tables;
    private final Path ledgerPath;
    private final StockStDailyPublicationStore store;

    public StockStDailyPublication(StockStDailyTables tables, Path ledgerPath) throws Exception {
        this.tables = Objects.requireNonNull(tables);
        this.ledgerPath = Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();
        this.store = new StockStDailyPublicationStore(this.ledgerPath);
    }

    /** Reserve the sole non-atomic publication slot before recording intent or renaming any QuestDB table. */
    public Result publish(String runId, String logicalTarget, String frozenPhysicalTarget, String target,
                          StockStDailyState.Prepared prepared, StockStDailyState.Verified stage,
                          BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(cancelled); DatasetDefinition.identifier(target);
        verifyRunBinding(runId, logicalTarget, frozenPhysicalTarget, target);
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("D012 publication cancelled before intent");
        var before = tables.snapshot(target);
        var staged = tables.snapshot(stage.table());
        if (!before.equals(prepared.before())
                || !tables.targetId(target, before.identity()).equals(frozenPhysicalTarget)
                || !samePhysical(staged.identity(), stage.snapshot().identity())
                || !StockStDailyState.sameContent(staged.content(), stage.snapshot().content()))
            throw new IllegalStateException("D012 target or staged snapshot changed before publication");
        Path receipt = Path.of(stage.receipt()).toAbsolutePath().normalize();
        Path runEvidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId).toAbsolutePath().normalize();
        Path realEvidence = runEvidence.toRealPath(), realReceipt = receipt.toRealPath();
        if (!realReceipt.startsWith(realEvidence) || Files.size(realReceipt) > 32L * 1024 * 1024)
            throw new IllegalStateException("D012 stage receipt is outside its bounded run evidence root");
        String receiptFingerprint = sha256(FileEvidenceStore.readBounded(realReceipt, 32 * 1024 * 1024, () -> new IllegalStateException("D012 stage receipt is outside its bounded run evidence root")));
        String backup = StockStDailyJobService.ISOLATED_TABLE_PREFIX + "backup_"
                + UUID.randomUUID().toString().replace("-", "");
        DatasetDefinition.identifier(backup);
        String id = "stk-st-publication-" + UUID.randomUUID();
        var intent = new Intent(id, runId, logicalTarget, frozenPhysicalTarget, target, backup, stage.table(),
                before.identity(), before.content(), staged.identity(), staged.content(), stage.outside(), stage.window(),
                prepared.from(), prepared.to(), stage.sourceRows(), stage.sourceFingerprint(), realReceipt.toString(), receiptFingerprint);
        Entry entry = reserve(intent);
        try {
            check(cancelled);
            rename(target, backup);
            entry = advance(entry, State.OLD_MOVED);
            check(cancelled);
            rename(stage.table(), target);
            entry = advance(entry, State.PUBLISHED);
            var verified = verifyPublished(entry);
            entry = advance(entry, State.VERIFIED);
            release(entry);
            return new Result(entry, Layout.PUBLISHED, verified.target(), verified.backup());
        } catch (Exception failure) {
            retainUncertain(entry, failure);
            throw new Uncertain(runId, id, failure);
        }
    }

    /** Recover only the exact journal-owned original/stage/published layouts after the writer has stopped. */
    public Result finish(String runId, boolean writerStopped) throws Exception {
        if (!writerStopped) throw new IllegalStateException("Stopped D012 writer proof required before publication recovery");
        Entry entry = forRun(runId);
        if (entry.state() == State.VERIFIED) {
            var verified = verifyPublished(entry);
            releaseIfOwned(entry);
            return new Result(entry, Layout.PUBLISHED, verified.target(), verified.backup());
        }
        requireMutex(entry);
        Layout layout = inspect(entry);
        if (layout == Layout.CONFLICT) throw new IllegalStateException("D012 publication tables differ from exact journal identities/content");
        if (entry.state() != State.IN_DOUBT) entry = advance(entry, State.IN_DOUBT);
        entry = advance(entry, State.RESUMING);
        try {
            if (layout == Layout.ORIGINAL) rename(entry.intent().target(), entry.intent().backup());
            if (layout != Layout.PUBLISHED) rename(entry.intent().stage(), entry.intent().target());
            entry = advance(entry, State.PUBLISHED);
            var verified = verifyPublished(entry);
            entry = advance(entry, State.VERIFIED);
            release(entry);
            return new Result(entry, Layout.PUBLISHED, verified.target(), verified.backup());
        } catch (Exception failure) {
            retainUncertain(entry, failure);
            throw new Uncertain(runId, entry.intent().id(), failure);
        }
    }

    public Entry forRun(String runId) throws Exception {
        return findForRun(runId).orElseThrow(() -> new IllegalArgumentException("No D012 publication intent for run " + runId));
    }

    public Optional<Entry> findForRun(String runId) throws Exception {
        return store.findForRun(runId, (json, state, revision) -> new Entry(
                JobDefinitionJson.mapper().readValue(json, Intent.class), State.valueOf(state), revision));
    }

    public void requireNoPendingPublication() throws Exception {
        store.requireNoPendingPublication();
    }

    private Result verifyPublished(Entry entry) throws Exception {
        if (inspect(entry) != Layout.PUBLISHED) throw new IllegalStateException("D012 published target differs from durable stage intent");
        var target = tables.snapshot(entry.intent().target());
        var backup = tables.snapshot(entry.intent().backup());
        return new Result(entry, Layout.PUBLISHED, target, backup);
    }

    private Layout inspect(Entry entry) throws Exception {
        Intent i = entry.intent();
        var target = snapshotIfPresent(i.target()); var backup = snapshotIfPresent(i.backup()); var stage = snapshotIfPresent(i.stage());
        if (matches(target, i.beforeIdentity(), i.before()) && backup == null && matches(stage, i.stageIdentity(), i.after()))
            return Layout.ORIGINAL;
        if (target == null && matches(backup, i.beforeIdentity(), i.before()) && matches(stage, i.stageIdentity(), i.after()))
            return Layout.OLD_MOVED;
        if (matches(target, i.stageIdentity(), i.after()) && matches(backup, i.beforeIdentity(), i.before()) && stage == null)
            return Layout.PUBLISHED;
        return Layout.CONFLICT;
    }

    private Entry reserve(Intent intent) throws Exception {
        store.reserve(intent.runId(), intent.id(), () -> JobDefinitionJson.mapper().writeValueAsString(intent));
        return new Entry(intent, State.PREPARED, 0);
    }

    private Entry advance(Entry prior, State next) throws Exception {
        boolean valid = next == State.IN_DOUBT && prior.state() != State.VERIFIED
                || prior.state() == State.PREPARED && next == State.OLD_MOVED
                || prior.state() == State.OLD_MOVED && next == State.PUBLISHED
                || prior.state() == State.PUBLISHED && next == State.VERIFIED
                || prior.state() == State.IN_DOUBT && next == State.RESUMING
                || prior.state() == State.RESUMING && next == State.PUBLISHED;
        if (!valid) throw new IllegalArgumentException("Invalid D012 publication transition");
        store.advance(prior.intent().runId(), prior.state().name(), prior.revision(), next.name());
        return new Entry(prior.intent(), next, prior.revision() + 1);
    }

    private void verifyRunBinding(String runId, String logicalTarget, String physicalTarget, String target) throws Exception {
        StockStDailyJobService.requireAdmittedTableName(target);
        var run = com.zoutrankil.data.repository.SyncRunLedger.openReadOnly(ledgerPath).getRun(runId);
        var frozen = JobDefinitionJson.mapper().readTree(run.frozenJson());
        var request = StockStDailySyncAdapter.restoreFrozenRequest(run.frozenJson(), run.targetId());
        StockStDailySyncAdapter.validateFrozenRequest(request);
        if (!"data.stk_st_daily".equals(run.jobId()) || run.jobVersion() != 1 || !logicalTarget.equals(run.targetId())
                || !logicalTarget.equals(frozen.path("parameters").path("targetId").asText())
                || !physicalTarget.equals(frozen.path("parameters").path("physicalTargetId").asText())
                || !StockStDailySyncJobOwner.DEFINITION.jobId().equals(frozen.path("definition").path("jobId").asText())
                || !StockExecutionTables.admitsStockStDaily(target)
                || "stk_st_daily".equals(target) && request.mode() != com.zoutrankil.data.domain.SyncJobDefinition.Mode.BACKFILL)
            throw new IllegalStateException("D012 publication differs from frozen logical/physical target binding");
    }

    private void requireMutex(Entry entry) throws SQLException {
        store.requireMutex(entry.intent().runId(), entry.intent().id());
    }

    private void release(Entry entry) throws SQLException {
        store.release(entry.intent().runId(), entry.intent().id());
    }

    private void releaseIfOwned(Entry entry) throws SQLException {
        store.releaseIfOwned(entry.intent().runId(), entry.intent().id());
    }

    private void retainUncertain(Entry entry, Exception original) {
        try {
            Entry latest = forRun(entry.intent().runId());
            if (latest.state() != State.IN_DOUBT && latest.state() != State.VERIFIED)
                advance(latest, State.IN_DOUBT);
            requireMutex(latest);
        } catch (Exception failure) { original.addSuppressed(failure); }
    }

    private StockStDailyState.Snapshot snapshotIfPresent(String table) throws Exception { return tables.snapshotIfPresent(table); }

    private static boolean matches(StockStDailyState.Snapshot snapshot, StockStDailyState.Identity identity,
                                  StockStDailyState.Content content) {
        return snapshot != null && samePhysical(snapshot.identity(), identity)
                && StockStDailyState.sameContent(snapshot.content(), content);
    }
    private static boolean samePhysical(StockStDailyState.Identity left, StockStDailyState.Identity right) {
        return left.id() == right.id() && left.directory().equals(right.directory());
    }
    private void rename(String from, String to) { tables.rename(from, to); }
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("D012 stage publication cancelled");
    }
    private static String sha256(byte[] bytes) throws Exception {
        return FileEvidenceStore.sha256(bytes);
    }
}
