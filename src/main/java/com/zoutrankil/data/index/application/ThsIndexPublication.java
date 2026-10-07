package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.port.ThsIndexTables;

import com.zoutrankil.data.index.domain.ThsIndexState;



import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.repository.ReferencePublicationJournal.State;

/** Whole-THS WAL publication. The two renames are not a cross-table atomic transaction. */
public final class ThsIndexPublication {
    public enum Layout { ORIGINAL,OLD_MOVED,PUBLISHED,CONFLICT }
    public record Result(ReferencePublicationJournal.Entry publication,ThsIndexState.Snapshot actual) {}
    @FunctionalInterface interface Hook { void after(State state) throws Exception; }
    public static final class Uncertain extends Exception {
        private final String runId;
        Uncertain(String run,Exception cause) { super("THS publication needs reconciliation: "+run,cause);runId=run; }
        public String runId() { return runId; }
    }
    private final ThsIndexTables targetAccess;
    private final Path ledgerPath;
    private final ReferencePublicationJournal journal;
    private final DatasetIntervalLock locks;
    private final Hook hook;
    public ThsIndexPublication(ThsIndexTables targetAccess,Path path) throws Exception { this(targetAccess,path,state->{}); }
    ThsIndexPublication(ThsIndexTables targetAccess,Path path,Hook hook) throws Exception {
        this.targetAccess=Objects.requireNonNull(targetAccess);
        ledgerPath=path;this.hook=hook;journal=new ReferencePublicationJournal(path,"ths_index");locks=new DatasetIntervalLock(path);
    }
    public Result publish(DatasetIntervalLock.Lease lease,String target,ThsIndexState.Prepared prepared,
                          ThsIndexState.Verified stage,BooleanSupplier cancelled) throws Exception {
        journal.requireLease(lease,false);
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        var before=targetAccess.open(target).snapshot();var replacement=targetAccess.open(stage.table()).snapshot();
        if(!before.equals(prepared.before()) || !replacement.equals(stage.snapshot()) || !replacement.rows().equals(prepared.rows()))
            throw new IllegalStateException("THS source target or stage changed before publication");
        String initial=targetAccess.identify(target,before.identity().id(),before.identity().directory());
        if(!SyncRunLedger.openReadOnly(ledgerPath).getRun(lease.runId()).targetId().equals(initial))
            throw new IllegalStateException("THS publication differs from frozen target");
        var entry=journal.create(new ReferencePublicationJournal.Intent("ths-publication-"+UUID.randomUUID(),"ths_index",lease.runId(),
                target,"java_ths_index_backup_"+UUID.randomUUID().toString().replace("-",""),stage.table(),initial,
                before.identity().id(),before.identity().directory(),replacement.identity().id(),before.fingerprint(),replacement.fingerprint()));
        try {
            hook.after(State.PREPARED);journal.requireLease(lease,false);
            if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            rename(target,entry.intent().backup());entry=journal.advance(entry,State.OLD_MOVED);hook.after(State.OLD_MOVED);
            rename(stage.table(),target);entry=journal.advance(entry,State.PUBLISHED);hook.after(State.PUBLISHED);
            return verified(entry);
        } catch(Exception failure) { retain(lease,failure);throw new Uncertain(lease.runId(),failure); }
    }
    public Layout inspect(String run) throws Exception {
        var i=journal.forRun(run).intent();requireEndpoint(i);
        var target=optional(i.target());var backup=optional(i.backup());var stage=optional(i.stage());
        if(matches(target,i.originalId(),i.beforeFingerprint()) && backup==null && matches(stage,i.replacementId(),i.afterFingerprint())) return Layout.ORIGINAL;
        if(target==null && matches(backup,i.originalId(),i.beforeFingerprint()) && matches(stage,i.replacementId(),i.afterFingerprint())) return Layout.OLD_MOVED;
        if(matches(target,i.replacementId(),i.afterFingerprint()) && matches(backup,i.originalId(),i.beforeFingerprint()) && stage==null) return Layout.PUBLISHED;
        return Layout.CONFLICT;
    }
    public Result finish(DatasetIntervalLock.Lease lease,boolean writerStopped) throws Exception {
        if(!writerStopped) throw new IllegalStateException("Stopped writer proof required");
        var actualLease=journal.requireLease(lease,true);var entry=journal.forRun(lease.runId());
        var layout=inspect(lease.runId());
        if(layout==Layout.CONFLICT) throw new IllegalStateException("Conflicting THS layout; no automatic mutation");
        if(entry.state()==State.VERIFIED) {
            if(layout!=Layout.PUBLISHED) throw new IllegalStateException("Verified publication drifted");
            return new Result(entry,targetAccess.open(entry.intent().target()).snapshot());
        }
        if(!actualLease.inDoubt()) locks.retainInDoubt(actualLease);
        if(entry.state()!=State.IN_DOUBT) entry=journal.advance(entry,State.IN_DOUBT);
        entry=journal.advance(entry,State.RESUMING);
        try {
            if(layout==Layout.ORIGINAL) rename(entry.intent().target(),entry.intent().backup());
            if(layout!=Layout.PUBLISHED) rename(entry.intent().stage(),entry.intent().target());
            entry=journal.advance(entry,State.PUBLISHED);return verified(entry);
        } catch(Exception failure) { retain(lease,failure);throw new Uncertain(lease.runId(),failure); }
    }
    private Result verified(ReferencePublicationJournal.Entry entry) throws Exception {
        if(inspect(entry.intent().runId())!=Layout.PUBLISHED) throw new IllegalStateException("THS published layout differs");
        var actual=targetAccess.open(entry.intent().target()).snapshot();
        return new Result(journal.advance(entry,State.VERIFIED),actual);
    }
    private void requireEndpoint(ReferencePublicationJournal.Intent intent) throws Exception {
        String identity=targetAccess.identify(intent.target(),intent.originalId(),intent.originalDirectory());
        if(!identity.equals(intent.initialTarget()) || !SyncRunLedger.openReadOnly(ledgerPath).getRun(intent.runId()).targetId().equals(identity))
            throw new IllegalStateException("THS endpoint differs from frozen publication");
    }
    private void retain(DatasetIntervalLock.Lease lease,Exception failure) {
        try { var e=journal.forRun(lease.runId());if(e.state()!=State.IN_DOUBT && e.state()!=State.VERIFIED) journal.advance(e,State.IN_DOUBT); }
        catch(Exception other) { failure.addSuppressed(other); }
        try { var actual=journal.requireLease(lease,true);if(!actual.inDoubt()) locks.retainInDoubt(actual); }
        catch(Exception other) { failure.addSuppressed(other); }
    }
    private void rename(String from,String to) { targetAccess.rename(from,to); }
    private ThsIndexState.Snapshot optional(String table) throws Exception {
        return targetAccess.snapshotIfPresent(table,"Renamed THS WAL did not settle; retain publication");
    }
    private static boolean matches(ThsIndexState.Snapshot snapshot,long id,String hash) {
        return snapshot!=null && snapshot.identity().id()==id && snapshot.fingerprint().equals(hash);
    }
}
