package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.zoutrankil.data.repository.ReferencePublicationJournal.State;

/** Journaled table swap; interruption requires stopped-writer reconciliation. */
public final class ThsMemberBoardPublication {
    public enum Layout { ORIGINAL, OLD_MOVED, PUBLISHED, CONFLICT }
    public record Result(ReferencePublicationJournal.Entry publication,
                         ThsMemberBoardStorage.Snapshot actual) {}
    @FunctionalInterface interface Hook { void after(State state) throws Exception; }
    public static final class Uncertain extends Exception {
        private final String runId;
        Uncertain(String runId, Exception cause) {
            super("THS member publication needs reconciliation: " + runId, cause);
            this.runId = runId;
        }
        public String runId() { return runId; }
    }

    private final JdbcTemplate jdbc;
    private final Path ledgerPath;
    private final ReferencePublicationJournal journal;
    private final DatasetIntervalLock locks;
    private final Hook hook;

    public ThsMemberBoardPublication(JdbcTemplate source, Path ledgerPath) throws Exception {
        this(source, ledgerPath, state -> {});
    }

    ThsMemberBoardPublication(JdbcTemplate source, Path ledgerPath, Hook hook) throws Exception {
        jdbc = new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));
        jdbc.setQueryTimeout(120);
        this.ledgerPath = ledgerPath;
        this.journal = new ReferencePublicationJournal(ledgerPath, "ths_member");
        this.locks = new DatasetIntervalLock(ledgerPath);
        this.hook = hook;
    }

    public Result publish(DatasetIntervalLock.Lease lease, ThsMemberBoardStaging.Prepared prepared,
                          ThsMemberBoardStaging.Verified stage, BooleanSupplier cancelled) throws Exception {
        journal.requireLease(lease, false);
        check(cancelled);
        var before = new ThsMemberBoardStorage(jdbc, prepared.target()).snapshot(prepared.board());
        var replacement = new ThsMemberBoardStorage(jdbc, stage.stage()).snapshot(prepared.board());
        if (!before.equals(prepared.before()) || !replacement.equals(stage.snapshot()))
            throw new IllegalStateException("THS target or stage changed before publication");
        String initial = StaticTargetIdentity.identify(jdbc, prepared.target(),
                before.identity().id(), before.identity().directory());
        if (!SyncRunLedger.openReadOnly(ledgerPath).getRun(lease.runId()).targetId().equals(initial))
            throw new IllegalStateException("THS member target differs from frozen run");
        var entry = journal.create(new ReferencePublicationJournal.Intent(
                "ths-member-publication-" + UUID.randomUUID(), "ths_member", lease.runId(), prepared.target(),
                "java_ths_member_backup_" + UUID.randomUUID().toString().replace("-", ""), stage.stage(),
                initial, before.identity().id(), before.identity().directory(), replacement.identity().id(),
                before.contentFingerprint(), replacement.contentFingerprint(), prepared.board()));
        try {
            hook.after(State.PREPARED); journal.requireLease(lease, false); check(cancelled);
            rename(prepared.target(), entry.intent().backup());
            entry = journal.advance(entry, State.OLD_MOVED); hook.after(State.OLD_MOVED);
            rename(stage.stage(), prepared.target());
            entry = journal.advance(entry, State.PUBLISHED); hook.after(State.PUBLISHED);
            return verified(entry);
        } catch (Exception failure) {
            retain(lease, failure);
            throw new Uncertain(lease.runId(), failure);
        }
    }

    public Layout inspect(String runId) throws Exception {
        var intent = journal.forRun(runId).intent();
        requireEndpoint(intent);
        var target = optional(intent.target(), intent.scope());
        var backup = optional(intent.backup(), intent.scope());
        var stage = optional(intent.stage(), intent.scope());
        if (matches(target, intent.originalId(), intent.beforeFingerprint()) && backup == null
                && matches(stage, intent.replacementId(), intent.afterFingerprint())) return Layout.ORIGINAL;
        if (target == null && matches(backup, intent.originalId(), intent.beforeFingerprint())
                && matches(stage, intent.replacementId(), intent.afterFingerprint())) return Layout.OLD_MOVED;
        if (matches(target, intent.replacementId(), intent.afterFingerprint())
                && matches(backup, intent.originalId(), intent.beforeFingerprint()) && stage == null)
            return Layout.PUBLISHED;
        return Layout.CONFLICT;
    }

    public Result finish(DatasetIntervalLock.Lease lease, boolean writerStopped) throws Exception {
        if (!writerStopped) throw new IllegalStateException("Stopped writer proof required");
        var actualLease = journal.requireLease(lease, true);
        var entry = journal.forRun(lease.runId());
        var layout = inspect(lease.runId());
        if (layout == Layout.CONFLICT) throw new IllegalStateException("Conflicting THS member layout");
        if (entry.state() == State.VERIFIED) {
            if (layout != Layout.PUBLISHED) throw new IllegalStateException("Verified publication drifted");
            return new Result(entry, new ThsMemberBoardStorage(jdbc, entry.intent().target())
                    .snapshot(entry.intent().scope()));
        }
        if (!actualLease.inDoubt()) locks.retainInDoubt(actualLease);
        if (entry.state() != State.IN_DOUBT) entry = journal.advance(entry, State.IN_DOUBT);
        entry = journal.advance(entry, State.RESUMING);
        try {
            if (layout == Layout.ORIGINAL) rename(entry.intent().target(), entry.intent().backup());
            if (layout != Layout.PUBLISHED) rename(entry.intent().stage(), entry.intent().target());
            entry = journal.advance(entry, State.PUBLISHED);
            return verified(entry);
        } catch (Exception failure) {
            retain(lease, failure);
            throw new Uncertain(lease.runId(), failure);
        }
    }

    private Result verified(ReferencePublicationJournal.Entry entry) throws Exception {
        if (inspect(entry.intent().runId()) != Layout.PUBLISHED)
            throw new IllegalStateException("THS member published layout differs");
        var actual = new ThsMemberBoardStorage(jdbc, entry.intent().target()).snapshot(entry.intent().scope());
        return new Result(journal.advance(entry, State.VERIFIED), actual);
    }

    private void requireEndpoint(ReferencePublicationJournal.Intent intent) throws Exception {
        if (!com.zoutrankil.data.domain.ThsIndex.validCode(intent.scope()))
            throw new IllegalStateException("Publication board scope missing");
        String identity = StaticTargetIdentity.identify(jdbc, intent.target(),
                intent.originalId(), intent.originalDirectory());
        if (!identity.equals(intent.initialTarget())
                || !SyncRunLedger.openReadOnly(ledgerPath).getRun(intent.runId()).targetId().equals(identity))
            throw new IllegalStateException("THS member endpoint differs from frozen publication");
    }

    private ThsMemberBoardStorage.Snapshot optional(String table, String board) throws Exception {
        if (jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?", table).isEmpty()) return null;
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Renamed THS WAL unresolved");
            Thread.sleep(50);
        }
        return new ThsMemberBoardStorage(jdbc, table).snapshot(board);
    }

    private static boolean matches(ThsMemberBoardStorage.Snapshot snapshot, long id, String fingerprint) throws Exception {
        return snapshot != null && snapshot.identity().id() == id
                && snapshot.contentFingerprint().equals(fingerprint);
    }

    private void retain(DatasetIntervalLock.Lease lease, Exception failure) {
        try {
            var entry = journal.forRun(lease.runId());
            if (entry.state() != State.IN_DOUBT && entry.state() != State.VERIFIED)
                journal.advance(entry, State.IN_DOUBT);
        } catch (Exception other) { failure.addSuppressed(other); }
        try {
            var actual = journal.requireLease(lease, true);
            if (!actual.inDoubt()) locks.retainInDoubt(actual);
        } catch (Exception other) { failure.addSuppressed(other); }
    }

    private void rename(String from, String to) { jdbc.execute("RENAME TABLE \"" + from + "\" TO \"" + to + "\""); }
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("THS member publication cancelled");
    }
}
