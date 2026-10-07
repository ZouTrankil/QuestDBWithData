package com.zoutrankil.data.flow.application;

import com.zoutrankil.data.flow.port.MoneyflowHsgtStagingPort;

import com.zoutrankil.data.flow.domain.MoneyflowHsgtState;
import com.zoutrankil.data.flow.domain.MoneyflowHsgtState.*;
import com.zoutrankil.data.flow.domain.MoneyflowHsgtRows;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.repository.ReferencePublicationJournal.State;

/** D027 stage/backup rename journal; every generation retains DAY/WAL/DEDUP=false. */
public final class MoneyflowHsgtPublication {
    public enum Layout { ORIGINAL, OLD_MOVED, PUBLISHED, CONFLICT }
    public record Result(ReferencePublicationJournal.Entry entry,Layout layout,
            MoneyflowHsgtState.Snapshot target,MoneyflowHsgtState.Snapshot backup) {}
    public static final class Uncertain extends Exception {
        private final String runId;
        public Uncertain(String runId,Exception cause){super("D027 table publication requires recovery: "+runId,cause);this.runId=runId;}
        public String runId(){return runId;}
    }
    public static final class Operation implements AutoCloseable {
        private final MoneyflowHsgtPublication owner;private final LockHolder lock;private boolean closed;
        private Operation(MoneyflowHsgtPublication owner,LockHolder lock){this.owner=owner;this.lock=lock;}
        @Override public synchronized void close()throws Exception{if(!closed){closed=true;lock.close();}}
    }
    private final MoneyflowHsgtStagingPort tables;private final Path ledgerPath;private final ReferencePublicationJournal journal;
    public MoneyflowHsgtPublication(MoneyflowHsgtStagingPort tables,Path ledgerPath)throws Exception {
        this.tables=Objects.requireNonNull(tables);
        this.ledgerPath=Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();new SyncRunLedger(this.ledgerPath);
        journal=new ReferencePublicationJournal(this.ledgerPath,"moneyflow_hsgt");
    }
    public Operation beginOperation()throws Exception{return beginOperation(null);}
    public Operation beginOperation(String exceptRun)throws Exception {
        LockHolder lock=acquireLock();try{requireNoPending(exceptRun);return new Operation(this,lock);}catch(Exception failure){lock.close();throw failure;}
    }
    public Result publish(Operation operation,MoneyflowHsgtState.Verified verified,BooleanSupplier cancelled)throws Exception {
        Objects.requireNonNull(verified);check(cancelled);
        if(operation==null||operation.owner!=this||operation.closed)throw new IllegalArgumentException("Active D027 publication lock required");
        var prepared=verified.prepared();requireNoPending(prepared.runId());
        var original=tables.open(prepared.target()).snapshot();
        var stage=tables.open(prepared.stage()).snapshot();
        if(!same(original,prepared.before())
                ||!tables.physicalTargetId(prepared.target(),original.identity()).equals(prepared.physicalTargetBefore())
                ||!same(stage,verified.snapshot())
                ||!tables.physicalTargetId(prepared.stage(),stage.identity()).equals(prepared.stagePhysicalTarget()))
            throw new IllegalStateException("D027 target or verified stage changed before publication");
        verifyStageReceipt(verified);
        String backup=com.zoutrankil.data.domain.MoneyflowHsgtDataset.ISOLATED_PREFIX+"backup_"+UUID.randomUUID().toString().replace("-","");
        String stageReceiptHash=sha(FileEvidenceStore.readBounded(Path.of(verified.receipt()), 4 * 1024 * 1024, () -> new IllegalStateException("D027 verified stage receipt escaped its run evidence root")));
        String scope=JobDefinitionJson.mapper().writeValueAsString(java.util.Map.of("stageReceipt",verified.receipt(),
                "stageReceiptFingerprint",stageReceiptHash,"requestFingerprint",prepared.requestFingerprint(),
                "windowFrom",prepared.from(),"windowTo",prepared.to(),"sourceRows",verified.authoritativeRows().size(),
                "replacementDirectory",stage.identity().directory()));
        var intent=new ReferencePublicationJournal.Intent("moneyflow-hsgt-publication-"+UUID.randomUUID(),"moneyflow_hsgt",
                prepared.runId(),prepared.target(),backup,prepared.stage(),prepared.physicalTargetBefore(),
                original.identity().id(),original.identity().directory(),stage.identity().id(),original.fingerprint(),stage.fingerprint(),scope);
        var entry=journal.create(intent);
        try{
            check(cancelled);rename(prepared.target(),backup);entry=journal.advance(entry,State.OLD_MOVED);
            check(cancelled);rename(prepared.stage(),prepared.target());entry=journal.advance(entry,State.PUBLISHED);
            var result=verifyPublished(entry);entry=journal.advance(entry,State.VERIFIED);
            return new Result(entry,Layout.PUBLISHED,result.target(),result.backup());
        }catch(Exception failure){
            try{var current=journal.forRun(prepared.runId());if(current.state()!=State.IN_DOUBT&&current.state()!=State.VERIFIED)journal.advance(current,State.IN_DOUBT);}
            catch(Exception journalFailure){failure.addSuppressed(journalFailure);}
            throw new Uncertain(prepared.runId(),failure);
        }
    }
    public Result finish(String runId,boolean writerStopped)throws Exception {
        if(!writerStopped)throw new IllegalStateException("D027 stopped-writer proof required before publication recovery");
        try(Operation operation=beginOperation(runId)){
            var entry=journal.forRun(runId);Layout layout=inspect(entry);if(layout==Layout.CONFLICT)throw new IllegalStateException("D027 table layout conflicts with durable journal identities/fingerprints");
            verifyJournalReceipt(entry);
            MoneyflowHsgtRecoveryProof.publication(ledgerPath,entry,tables.logicalTargetId(entry.intent().target()));
            if(entry.state()==State.VERIFIED){if(layout!=Layout.PUBLISHED)throw new IllegalStateException("Verified D027 publication drifted");return verifyPublished(entry);}
            if(entry.state()!=State.IN_DOUBT)entry=journal.advance(entry,State.IN_DOUBT);
            entry=journal.advance(entry,State.RESUMING);
            try{
                if(layout==Layout.ORIGINAL)rename(entry.intent().target(),entry.intent().backup());
                if(layout!=Layout.PUBLISHED)rename(entry.intent().stage(),entry.intent().target());
                entry=journal.advance(entry,State.PUBLISHED);var result=verifyPublished(entry);
                entry=journal.advance(entry,State.VERIFIED);return new Result(entry,Layout.PUBLISHED,result.target(),result.backup());
            }catch(Exception failure){
                try{var current=journal.forRun(runId);if(current.state()!=State.IN_DOUBT&&current.state()!=State.VERIFIED)journal.advance(current,State.IN_DOUBT);}
                catch(Exception journalFailure){failure.addSuppressed(journalFailure);}
                throw new Uncertain(runId,failure);
            }
        }
    }
    /** Safely restart a failed source run by dropping only its unpublished isolated stage. */
    public void discardStageOnly(String target,String runId,boolean writerStopped)throws Exception {
        if(!writerStopped)throw new IllegalStateException("D027 writer-stopped confirmation required");
        try(Operation operation=beginOperation(runId)){
            if(journal.findForRun(runId).isPresent())throw new IllegalStateException("D027 run has a publication journal; finish that publication instead of discarding its stage");
            var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=ledger.getRun(runId);var runEntry=ledger.get(runId);
            if(!MoneyflowHsgtSyncJobOwner.DEFINITION.jobId().equals(run.jobId())||run.jobVersion()!=MoneyflowHsgtSyncJobOwner.DEFINITION.version()
                    ||!tables.logicalTargetId(target).equals(run.targetId())
                    ||!java.util.Set.of(SyncRunState.IN_DOUBT,SyncRunState.FAILED,SyncRunState.PARTIAL,SyncRunState.CANCELLED).contains(runEntry.state()))
                throw new IllegalStateException("D027 stage discard requires a stopped failed/cancelled/partial run for this logical target");
            Path evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
            MoneyflowHsgtStaging.discardUnpublished(tables,target,runId,evidence,true);
            JsonNode frozen=JobDefinitionJson.mapper().readTree(run.frozenJson());
            LocalDate from=LocalDate.parse(frozen.path("from").asText()),to=LocalDate.parse(frozen.path("to").asText());
            var locks=new DatasetIntervalLock(ledgerPath);var scope=new DatasetIntervalLock.Scope("moneyflow_hsgt",from,to);var lease=locks.findOwned(runId,scope);
            if(lease!=null){if(!lease.inDoubt()){locks.retainInDoubt(lease);lease=locks.findOwned(runId,scope);}locks.releaseAfterReconciliation(lease,true,true);}
        }
    }
    public java.util.Optional<ReferencePublicationJournal.Entry> findForRun(String runId)throws Exception{return journal.findForRun(runId);}
    public boolean runClosedSuccessfully(String runId)throws Exception {
        var entry=SyncRunLedger.openReadOnly(ledgerPath).get(runId);
        return entry.state()==SyncRunState.VERIFIED||entry.state()==SyncRunState.VERIFIED_EMPTY;
    }
    public void requireNoPendingPublication()throws Exception{requireNoPending(null);}
    private void requireNoPending(String exceptRun)throws Exception {
        journal.visitSummaries(1001,false,ReferencePublicationJournal.ConnectionPolicy.DRIVER_DEFAULTS,(summary,rowNumber)->{
            if(rowNumber>1000)throw new IllegalStateException("D027 publication journal scan exceeds 1000 rows");String runId=summary.runId();
            if(Objects.equals(exceptRun,runId))return;
            if(!State.VERIFIED.name().equals(summary.state())||!runClosedSuccessfully(runId))
                throw new IllegalStateException("D027 publication or owning ledger run requires recovery/finalization: "+runId);
        });
        Path root=ledgerPath.getParent().resolve("sync-evidence");if(!Files.exists(root))return;
        if(Files.isSymbolicLink(root)||!Files.isDirectory(root))throw new IllegalStateException("D027 evidence root is invalid");
        int count=0;try(var runs=Files.newDirectoryStream(root)){for(Path dir:runs){if(++count>50_000)throw new IllegalStateException("D027 unresolved-stage scan exceeds 50000 runs");
            if(Files.isSymbolicLink(dir)||!Files.isDirectory(dir))continue;if(!MoneyflowHsgtStaging.hasStageIntent(dir))continue;
            String run=dir.getFileName().toString();if(Objects.equals(exceptRun,run))continue;
            var prior=journal.findForRun(run);if(prior.isPresent()&&prior.get().state()==State.VERIFIED&&runClosedSuccessfully(run))continue;
            throw new IllegalStateException("Unresolved D027 stage-only artifact requires explicit recovery: "+run);}}
    }
    private Layout inspect(ReferencePublicationJournal.Entry entry)throws Exception {
        var i=entry.intent();boolean target=exists(i.target()),backup=exists(i.backup()),stage=exists(i.stage());
        String replacementDirectory=JobDefinitionJson.mapper().readTree(i.scope()).path("replacementDirectory").asText("");
        if(replacementDirectory.isBlank())return Layout.CONFLICT;
        if(target&&backup&&!stage&&matches(i.target(),i.replacementId(),replacementDirectory,i.afterFingerprint())&&matches(i.backup(),i.originalId(),i.originalDirectory(),i.beforeFingerprint()))return Layout.PUBLISHED;
        if(!target&&backup&&stage&&matches(i.backup(),i.originalId(),i.originalDirectory(),i.beforeFingerprint())&&matches(i.stage(),i.replacementId(),replacementDirectory,i.afterFingerprint()))return Layout.OLD_MOVED;
        if(target&&!backup&&stage&&matches(i.target(),i.originalId(),i.originalDirectory(),i.beforeFingerprint())&&matches(i.stage(),i.replacementId(),replacementDirectory,i.afterFingerprint()))return Layout.ORIGINAL;
        return Layout.CONFLICT;
    }
    private Result verifyPublished(ReferencePublicationJournal.Entry entry)throws Exception {
        if(inspect(entry)!=Layout.PUBLISHED)throw new IllegalStateException("D027 published target differs from journal stage snapshot");
        return new Result(entry,Layout.PUBLISHED,tables.open(entry.intent().target()).snapshot(),
                tables.open(entry.intent().backup()).snapshot());
    }
    private boolean matches(String table,long id,String directory,String fingerprint)throws Exception {
        var snapshot=tables.open(table).snapshot();return snapshot.identity().id()==id
                &&snapshot.identity().directory().equals(directory)&&snapshot.fingerprint().equals(fingerprint);
    }
    private boolean exists(String table){return tables.tableCount(table)==1;}
    private void verifyStageReceipt(MoneyflowHsgtState.Verified verified)throws Exception {
        Path receipt=Path.of(verified.receipt()).toRealPath();Path runRoot=verified.prepared().runEvidence().toRealPath();
        if(!receipt.startsWith(runRoot)||Files.size(receipt)<1||Files.size(receipt)>4*1024*1024)throw new IllegalStateException("D027 verified stage receipt escaped its run evidence root");
        JsonNode proof=JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(receipt, 4 * 1024 * 1024, () -> new IllegalStateException("D027 verified stage receipt escaped its run evidence root")));var p=verified.prepared();
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(p.runId());
        if(!"moneyflow_hsgt".equals(proof.path("dataset").asText())||!proof.path("sourceComplete").asBoolean(false)
                ||proof.path("dedup").asBoolean(true)||!p.runId().equals(proof.path("runId").asText())
                ||!p.target().equals(proof.path("target").asText())||!p.stage().equals(proof.path("stage").asText())
                ||!p.requestFingerprint().equals(proof.path("requestFingerprint").asText())
                ||!SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId()).equals(p.requestFingerprint())
                ||proof.path("sourceRows").asInt(-1)!=verified.authoritativeRows().size()
                ||!MoneyflowHsgtRows.sameRows(verified.authoritativeRows(),tables.open(p.stage()).window(p.from(),p.to()).rows()))
            throw new IllegalStateException("D027 publication source proof differs from frozen request/staged window");
    }
    private void verifyJournalReceipt(ReferencePublicationJournal.Entry entry)throws Exception {
        JsonNode scope=JobDefinitionJson.mapper().readTree(entry.intent().scope());String raw=scope.path("stageReceipt").asText("");
        Path receipt=Path.of(raw).toAbsolutePath().normalize();Path root=ledgerPath.getParent().resolve("sync-evidence").resolve(entry.intent().runId()).toRealPath();
        if(Files.isSymbolicLink(receipt)||!Files.isRegularFile(receipt,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.size(receipt)<1||Files.size(receipt)>4*1024*1024
                ||!receipt.toRealPath().startsWith(root)||!sha(FileEvidenceStore.readBounded(receipt, 4 * 1024 * 1024, () -> new IllegalStateException("D027 verified stage receipt escaped its run evidence root"))).equals(scope.path("stageReceiptFingerprint").asText()))
            throw new IllegalStateException("D027 journal stage receipt hash/path is invalid");
        JsonNode proof=JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(receipt, 4 * 1024 * 1024, () -> new IllegalStateException("D027 verified stage receipt escaped its run evidence root")));
        JsonNode stageIdentity=proof.path("stageAfter").path("identity"),beforeIdentity=proof.path("before").path("identity");
        if(!proof.path("sourceComplete").asBoolean(false)||proof.path("dedup").asBoolean(true)
                ||!entry.intent().runId().equals(proof.path("runId").asText())||!entry.intent().stage().equals(proof.path("stage").asText())
                ||!entry.intent().target().equals(proof.path("target").asText())
                ||!entry.intent().afterFingerprint().equals(proof.path("stageAfter").path("fingerprint").asText())
                ||stageIdentity.path("id").asLong(-1)!=entry.intent().replacementId()
                ||!stageIdentity.path("directory").asText().equals(scope.path("replacementDirectory").asText())
                ||beforeIdentity.path("id").asLong(-1)!=entry.intent().originalId()
                ||!beforeIdentity.path("directory").asText().equals(entry.intent().originalDirectory()))
            throw new IllegalStateException("D027 publication journal and verified-stage receipt disagree");
        JsonNode refs=proof.path("sourceReceipts");if(!refs.isArray()||refs.isEmpty())throw new IllegalStateException("D027 verified stage lacks source receipt references");
        String sourceTable=exists(entry.intent().stage())?entry.intent().stage():entry.intent().target();
        var sourceRoot=root.toRealPath();int returned=0;
        for(JsonNode ref:refs){
            LocalDate from=LocalDate.parse(ref.path("fromInclusive").asText());LocalDate to=LocalDate.parse(ref.path("toInclusive").asText());
            String hash=ref.path("sourceFingerprint").asText("");Path source=Path.of(ref.path("responseEvidence").asText("")).toAbsolutePath().normalize();
            if(!source.startsWith(sourceRoot)||Files.isSymbolicLink(source)||!Files.isRegularFile(source,java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("D027 journal source receipt escaped its run evidence root");
            var page=MoneyflowHsgtSource.reopen(source,hash,from,to);
            if(page.rows().size()!=ref.path("rows").asInt(-1)
                    ||!MoneyflowHsgtRows.sameRows(page.rows(),tables.open(sourceTable).window(from,to).rows()))
                throw new IllegalStateException("D027 journal source receipt differs from the staged/published date window");
            returned=Math.addExact(returned,page.rows().size());
        }
        if(returned!=proof.path("sourceRows").asInt(-1)||returned!=scope.path("sourceRows").asInt(-2))
            throw new IllegalStateException("D027 journal source row total differs from receipt-backed stage proof");
    }
    private void rename(String source,String target){tables.rename(source,target);}
    private LockHolder acquireLock()throws Exception {
        Path path=ledgerPath.resolveSibling(ledgerPath.getFileName()+".d027-moneyflow-hsgt.lock");Files.createDirectories(path.getParent());
        var channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock;
        try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){channel.close();throw new IllegalStateException("D027 publication is already active",busy);}
        if(lock==null){channel.close();throw new IllegalStateException("D027 publication is already active");}return new LockHolder(channel,lock);
    }
    private static String sha(byte[] bytes)throws Exception{return FileEvidenceStore.sha256(bytes);}
    private static boolean same(MoneyflowHsgtState.Snapshot a,MoneyflowHsgtState.Snapshot b){return MoneyflowHsgtRows.sameContent(a,b)
            &&a.identity().id()==b.identity().id()&&a.identity().directory().equals(b.identity().directory())&&a.identity().writerTxn()==b.identity().writerTxn();}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("D027 table publication cancelled");}
    private static final class LockHolder implements AutoCloseable {
        private final FileChannel channel;private final FileLock lock;LockHolder(FileChannel channel,FileLock lock){this.channel=channel;this.lock=lock;}
        @Override public void close()throws IOException{try{lock.release();}finally{channel.close();}}
    }
}
