package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.questdbwithdata.repository.StockDetailPublicationJournal.State;

/** Non-atomic two-rename publication. Backups survive verification; unknown outcomes retain exclusion. */
public final class StockDetailInfoPublication {
    public enum Layout { ORIGINAL, OLD_MOVED, PUBLISHED, CONFLICT }
    public record Inspection(String publicationId,Layout layout,String detail) {}
    public record Result(StockDetailPublicationJournal.Entry entry,Inspection inspection) {}
    public static final class Uncertain extends Exception {
        private final String publicationId;
        Uncertain(String id,Exception cause) { super("Static publication requires reconciliation: "+id,cause);publicationId=id; }
        public String publicationId() { return publicationId; }
    }
    @FunctionalInterface interface PhaseHook { void after(State state) throws Exception; }
    private final JdbcTemplate jdbc;
    private final StockDetailPublicationJournal journal;
    private final DatasetIntervalLock locks;
    private final PhaseHook hook;
    public StockDetailInfoPublication(JdbcTemplate jdbc,Path ledger) throws Exception { this(jdbc,ledger,state->{}); }
    StockDetailInfoPublication(JdbcTemplate jdbc,Path ledger,PhaseHook hook) throws Exception {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(20);
        this.journal=new StockDetailPublicationJournal(ledger);this.locks=new DatasetIntervalLock(ledger);this.hook=hook;
    }
    public Result publish(DatasetIntervalLock.Lease lease,String target,StockDetailInfoStaging.Prepared prepared,
                          StockDetailInfoStaging.Verified stage,BooleanSupplier cancelled) throws Exception {
        return publishInternal(lease,target,prepared,stage,cancelled,false);
    }
    Result publishAfterStoppedWriter(DatasetIntervalLock.Lease lease,String target,StockDetailInfoStaging.Prepared prepared,
                                    StockDetailInfoStaging.Verified stage,boolean writerStopped) throws Exception {
        if(!writerStopped || journal.findSingleForRun(lease.runId())!=null)
            throw new IllegalStateException("Stopped writer and absence of publication intent required");
        return publishInternal(lease,target,prepared,stage,()->false,true);
    }
    private Result publishInternal(DatasetIntervalLock.Lease lease,String target,StockDetailInfoStaging.Prepared prepared,
                          StockDetailInfoStaging.Verified stage,BooleanSupplier cancelled,boolean recovering) throws Exception {
        DatasetDefinition.identifier(target);journal.requireLease(lease,recovering);
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        var before=new StockDetailInfoStorage(jdbc,target).snapshot();
        var replacement=new StockDetailInfoStorage(jdbc,stage.table()).snapshot();
        if(!before.identity().equals(prepared.before().identity()) || !before.fingerprint().equals(prepared.before().fingerprint()))
            throw new IllegalStateException("Original target changed since preparation");
        if(!replacement.identity().equals(stage.snapshot().identity()) || !replacement.rows().equals(prepared.rows())
                || !replacement.fingerprint().equals(stage.snapshot().fingerprint()))
            throw new IllegalStateException("Staging identity or values changed since verification");
        String id="publication-"+UUID.randomUUID();String backup="java_stock_detail_backup_"+UUID.randomUUID().toString().replace("-","");
        var intent=new StockDetailPublicationJournal.Intent(id,lease.runId(),target,backup,stage.table(),
                before.identity().id(),replacement.identity().id(),before.fingerprint(),replacement.fingerprint());
        var entry=journal.create(intent);
        try {
            hook.after(State.PREPARED);
            journal.requireLease(lease,recovering);
            if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            jdbc.execute("RENAME TABLE "+target+" TO "+backup);
            entry=journal.advance(entry,State.OLD_RENAMED);hook.after(State.OLD_RENAMED);
            // Once the old name has moved, finish the bounded switch or retain an uncertain state for reconciliation.
            jdbc.execute("RENAME TABLE "+stage.table()+" TO "+target);
            entry=journal.advance(entry,State.NEW_RENAMED);hook.after(State.NEW_RENAMED);
            var inspected=inspect(id);
            if(inspected.layout()!=Layout.PUBLISHED) throw new IllegalStateException("Published tables differ from frozen values");
            entry=journal.advance(entry,State.VERIFIED);
            return new Result(entry,inspected);
        } catch(Exception failure) {
            try { var latest=journal.get(id);if(latest.state()!=State.VERIFIED) journal.advance(latest,State.IN_DOUBT); }
            catch(Exception journalFailure) { failure.addSuppressed(journalFailure); }
            try { locks.retainInDoubt(lease); } catch(Exception lockFailure) { failure.addSuppressed(lockFailure); }
            throw new Uncertain(id,failure);
        }
    }
    /** Read-only reconciliation view. It never repeats a rename or assumes a failed call had no effect. */
    public Inspection inspect(String id) throws Exception {
        var i=journal.get(id).intent();
        var target=snapshotIfPresent(i.target());var backup=snapshotIfPresent(i.backup());var stage=snapshotIfPresent(i.stage());
        if(matches(target,i.oldTableId(),i.beforeFingerprint()) && backup==null && matches(stage,i.newTableId(),i.afterFingerprint()))
            return new Inspection(id,Layout.ORIGINAL,"Original and staged values intact; no published change observed");
        if(target==null && matches(backup,i.oldTableId(),i.beforeFingerprint()) && matches(stage,i.newTableId(),i.afterFingerprint()))
            return new Inspection(id,Layout.OLD_MOVED,"Original retained as backup; target name absent");
        if(matches(target,i.newTableId(),i.afterFingerprint()) && matches(backup,i.oldTableId(),i.beforeFingerprint()) && stage==null)
            return new Inspection(id,Layout.PUBLISHED,"New target and prior backup both exactly match frozen fingerprints");
        return new Inspection(id,Layout.CONFLICT,"Unexpected table identity, content or name layout; no automatic mutation");
    }
    /** Explicit recovery only after the caller proves the old writer stopped. Never replaces an already published target. */
    public Result restoreOriginal(DatasetIntervalLock.Lease lease,String id,boolean writerStopped) throws Exception {
        var entry=prepareRecovery(lease,id,writerStopped);
        var observed=inspect(id);
        if(observed.layout()!=Layout.ORIGINAL && observed.layout()!=Layout.OLD_MOVED)
            throw new IllegalStateException("Original restoration requires intact old/stage tables and no published target");
        entry=journal.advance(entry,State.RESTORING_OLD);
        try {
            if(observed.layout()==Layout.OLD_MOVED)
                jdbc.execute("RENAME TABLE "+entry.intent().backup()+" TO "+entry.intent().target());
            var restored=inspect(id);
            if(restored.layout()!=Layout.ORIGINAL) throw new IllegalStateException("Original recovery readback mismatch");
            return new Result(journal.advance(entry,State.ROLLED_BACK),restored);
        } catch(Exception failure) {
            try { journal.advance(journal.get(id),State.IN_DOUBT); }
            catch(Exception journalFailure) { failure.addSuppressed(journalFailure); }
            throw new Uncertain(id,failure);
        }
    }
    /** Finish only the remaining renames after the old writer stopped and exact layouts were inspected. */
    public Result finishInterrupted(DatasetIntervalLock.Lease lease,String id,boolean writerStopped) throws Exception {
        var entry=prepareRecovery(lease,id,writerStopped);var layout=inspect(id).layout();
        if(layout!=Layout.ORIGINAL && layout!=Layout.OLD_MOVED)
            throw new IllegalStateException("Finishing publication requires exact original/staged layout");
        entry=journal.advance(entry,State.RESUMING);
        try {
            if(layout==Layout.ORIGINAL) jdbc.execute("RENAME TABLE "+entry.intent().target()+" TO "+entry.intent().backup());
            jdbc.execute("RENAME TABLE "+entry.intent().stage()+" TO "+entry.intent().target());
            entry=journal.advance(entry,State.NEW_RENAMED);
            var actual=inspect(id);
            if(actual.layout()!=Layout.PUBLISHED) throw new IllegalStateException("Resumed publication readback differs");
            return new Result(journal.advance(entry,State.VERIFIED),actual);
        } catch(Exception failure) {
            try { journal.advance(journal.get(id),State.IN_DOUBT); }
            catch(Exception journalFailure) { failure.addSuppressed(journalFailure); }
            throw new Uncertain(id,failure);
        }
    }
    /** Finalize a fully published layout after lost acknowledgement, without any QuestDB mutation. */
    public Result acceptPublished(DatasetIntervalLock.Lease lease,String id,boolean writerStopped) throws Exception {
        var entry=prepareRecovery(lease,id,writerStopped);
        var actual=inspect(id);
        if(actual.layout()!=Layout.PUBLISHED) throw new IllegalStateException("Published target and retained backup must match exactly");
        return new Result(journal.advance(entry,State.VERIFIED),actual);
    }
    private StockDetailPublicationJournal.Entry prepareRecovery(DatasetIntervalLock.Lease lease,String id,
                                                               boolean writerStopped) throws Exception {
        if(!writerStopped) throw new IllegalStateException("Stopped-writer proof required before recovery");
        boolean uncertain=journal.requireLease(lease,true);var entry=journal.get(id);
        if(!entry.intent().runId().equals(lease.runId()) || entry.state()==State.VERIFIED || entry.state()==State.ROLLED_BACK)
            throw new IllegalStateException("Recovery requires the owning nonterminal publication");
        // A hard stop can leave PREPARED/OLD_RENAMED/NEW_RENAMED/RESTORING_OLD with an ordinary retained lease.
        if(!uncertain) locks.retainInDoubt(lease);
        return entry.state()==State.IN_DOUBT?entry:journal.advance(entry,State.IN_DOUBT);
    }
    private StockDetailInfoStorage.Snapshot snapshotIfPresent(String table) throws Exception {
        var rows=jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",table);
        return rows.isEmpty()?null:new StockDetailInfoStorage(jdbc,table).snapshot();
    }
    private static boolean matches(StockDetailInfoStorage.Snapshot snapshot,long id,String fingerprint) {
        return snapshot!=null && snapshot.identity().id()==id && snapshot.fingerprint().equals(fingerprint);
    }
}
