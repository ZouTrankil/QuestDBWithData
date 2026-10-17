package com.zoutrankil.data.margin.application;

import com.zoutrankil.data.margin.domain.MarginAllState;
import com.zoutrankil.data.margin.domain.MarginAllState.*;
import com.zoutrankil.data.margin.domain.MarginAllRows;
import com.zoutrankil.data.margin.port.MarginAllTables;

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
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.repository.ReferencePublicationJournal.State;

/** D028 stage/backup rename journal; every generation retains YEAR/WAL/DEDUP=false. */
public final class MarginAllPublication {
    public enum Layout { ORIGINAL, OLD_MOVED, PUBLISHED, CONFLICT }
    public record Result(ReferencePublicationJournal.Entry entry,Layout layout,
            MarginAllState.Snapshot target,MarginAllState.Snapshot backup) {}
    public static final class Uncertain extends Exception {
        private final String runId;
        public Uncertain(String runId,Exception cause){super("D028 table publication requires recovery: "+runId,cause);this.runId=runId;}
        public String runId(){return runId;}
    }
    public static final class Operation implements AutoCloseable {
        private final MarginAllPublication owner;private final LockHolder lock;private boolean closed;
        private Operation(MarginAllPublication owner,LockHolder lock){this.owner=owner;this.lock=lock;}
        @Override public synchronized void close()throws Exception{if(!closed){closed=true;lock.close();}}
    }
    private final MarginAllTables tables;private final Path ledgerPath;private final ReferencePublicationJournal journal;
    public MarginAllPublication(MarginAllTables tables,Path ledgerPath)throws Exception {
        this.tables=Objects.requireNonNull(tables);
        this.ledgerPath=Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();new SyncRunLedger(this.ledgerPath);
        journal=new ReferencePublicationJournal(this.ledgerPath,"margin_all");
    }
    public Operation beginOperation()throws Exception{return beginOperation(null);}
    public Operation beginOperation(String exceptRun)throws Exception {
        LockHolder lock=acquireLock();try{requireNoPending(exceptRun);return new Operation(this,lock);}catch(Exception failure){lock.close();throw failure;}
    }
    public Result publish(Operation operation,MarginAllState.Verified verified,BooleanSupplier cancelled)throws Exception {
        Objects.requireNonNull(verified);check(cancelled);
        if(operation==null||operation.owner!=this||operation.closed)throw new IllegalArgumentException("Active D028 publication lock required");
        var prepared=verified.prepared();requireNoPending(prepared.runId());
        var original=tables.open(prepared.target()).snapshot();
        var stage=tables.open(prepared.stage()).snapshot();
        if(!same(original,prepared.before())
                ||!tables.physicalTargetId(prepared.target(),original.identity()).equals(prepared.physicalTargetBefore())
                ||!same(stage,verified.snapshot())
                ||!tables.physicalTargetId(prepared.stage(),stage.identity()).equals(prepared.stagePhysicalTarget()))
            throw new IllegalStateException("D028 target or verified stage changed before publication");
        verifyStageReceipt(verified);
        String backup=com.zoutrankil.data.domain.MarginAllDataset.ISOLATED_PREFIX+"backup_"+UUID.randomUUID().toString().replace("-","");
        String stageReceiptHash=sha(FileEvidenceStore.readBounded(Path.of(verified.receipt()), 4 * 1024 * 1024, () -> new IllegalStateException("D028 verified stage receipt escaped its run evidence root")));
        String scope=JobDefinitionJson.mapper().writeValueAsString(java.util.Map.of("stageReceipt",verified.receipt(),
                "stageReceiptFingerprint",stageReceiptHash,"requestFingerprint",prepared.requestFingerprint(),
                "windowFrom",prepared.from(),"windowTo",prepared.to(),"sourceRows",verified.authoritativeRows().size()));
        var intent=new ReferencePublicationJournal.Intent("margin-all-publication-"+UUID.randomUUID(),"margin_all",
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
        if(!writerStopped)throw new IllegalStateException("D028 stopped-writer proof required before publication recovery");
        try(Operation operation=beginOperation(runId)){
            var entry=journal.forRun(runId);Layout layout=inspect(entry);if(layout==Layout.CONFLICT)throw new IllegalStateException("D028 table layout conflicts with durable journal identities/fingerprints");
            verifyJournalReceipt(entry,layout);
            if(entry.state()==State.VERIFIED){if(layout!=Layout.PUBLISHED)throw new IllegalStateException("Verified D028 publication drifted");return verifyPublished(entry);}
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
        if(!writerStopped)throw new IllegalStateException("D028 writer-stopped confirmation required");
        try(Operation operation=beginOperation(runId)){
            if(journal.findForRun(runId).isPresent())throw new IllegalStateException("D028 run has a publication journal; finish that publication instead of discarding its stage");
            var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=ledger.getRun(runId);var runEntry=ledger.get(runId);
            if(!MarginAllSyncJobOwner.DEFINITION.jobId().equals(run.jobId())||run.jobVersion()!=MarginAllSyncJobOwner.DEFINITION.version()
                    ||!tables.logicalTargetId(target).equals(run.targetId())
                    ||!java.util.Set.of(SyncRunState.IN_DOUBT,SyncRunState.FAILED,SyncRunState.PARTIAL,SyncRunState.CANCELLED).contains(runEntry.state()))
                throw new IllegalStateException("D028 stage discard requires a stopped failed/cancelled/partial run for this logical target");
            Path evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
            MarginAllStaging.discardUnpublished(tables,target,runId,evidence,ledgerPath,true);
            JsonNode frozen=JobDefinitionJson.mapper().readTree(run.frozenJson());
            LocalDate from=LocalDate.parse(frozen.path("from").asText()),to=LocalDate.parse(frozen.path("to").asText());
            var locks=new DatasetIntervalLock(ledgerPath);var scope=new DatasetIntervalLock.Scope("margin_all",from,to);var lease=locks.findOwned(runId,scope);
            if(lease!=null){if(!lease.inDoubt()){locks.retainInDoubt(lease);lease=locks.findOwned(runId,scope);}locks.releaseAfterReconciliation(lease,true,true);}
        }
    }
    public java.util.Optional<ReferencePublicationJournal.Entry> findForRun(String runId)throws Exception{return journal.findForRun(runId);}
    public void requireNoPendingPublication()throws Exception{requireNoPending(null);}
    private void requireNoPending(String exceptRun)throws Exception {
        journal.visitSummaries(32,true,ReferencePublicationJournal.ConnectionPolicy.DRIVER_DEFAULTS,(summary,rowNumber)->{
            if(!Objects.equals(exceptRun,summary.runId()))throw new IllegalStateException("Unresolved D028 publication requires finish: "+summary.runId());
        });
        Path root=ledgerPath.getParent().resolve("sync-evidence");if(!Files.exists(root))return;
        if(Files.isSymbolicLink(root)||!Files.isDirectory(root))throw new IllegalStateException("D028 evidence root is invalid");
        int count=0;try(var runs=Files.newDirectoryStream(root)){for(Path dir:runs){if(++count>50_000)throw new IllegalStateException("D028 unresolved-stage scan exceeds 50000 runs");
            if(Files.isSymbolicLink(dir)||!Files.isDirectory(dir))continue;if(!MarginAllStaging.hasStageIntent(dir))continue;
            String run=dir.getFileName().toString();if(Objects.equals(exceptRun,run))continue;
            var prior=journal.findForRun(run);if(prior.isPresent()&&prior.get().state()==State.VERIFIED)continue;
            throw new IllegalStateException("Unresolved D028 stage-only artifact requires explicit recovery: "+run);}}
    }
    private Layout inspect(ReferencePublicationJournal.Entry entry)throws Exception {
        var i=entry.intent();boolean target=exists(i.target()),backup=exists(i.backup()),stage=exists(i.stage());
        if(target&&backup&&!stage&&matches(i.target(),i.replacementId(),i.afterFingerprint())&&matches(i.backup(),i.originalId(),i.beforeFingerprint()))return Layout.PUBLISHED;
        if(!target&&backup&&stage&&matches(i.backup(),i.originalId(),i.beforeFingerprint())&&matches(i.stage(),i.replacementId(),i.afterFingerprint()))return Layout.OLD_MOVED;
        if(target&&!backup&&stage&&matches(i.target(),i.originalId(),i.beforeFingerprint())&&matches(i.stage(),i.replacementId(),i.afterFingerprint()))return Layout.ORIGINAL;
        return Layout.CONFLICT;
    }
    private Result verifyPublished(ReferencePublicationJournal.Entry entry)throws Exception {
        if(inspect(entry)!=Layout.PUBLISHED)throw new IllegalStateException("D028 published target differs from journal stage snapshot");
        return new Result(entry,Layout.PUBLISHED,tables.open(entry.intent().target()).snapshot(),
                tables.open(entry.intent().backup()).snapshot());
    }
    private boolean matches(String table,long id,String fingerprint)throws Exception {
        var snapshot=tables.open(table).snapshot();return snapshot.identity().id()==id&&snapshot.fingerprint().equals(fingerprint);
    }
    private boolean exists(String table){return tables.tableCount(table)==1;}
    private void verifyStageReceipt(MarginAllState.Verified verified)throws Exception {
        Path receipt=Path.of(verified.receipt()).toRealPath();Path runRoot=verified.prepared().runEvidence().toRealPath();
        if(!receipt.startsWith(runRoot)||Files.size(receipt)<1||Files.size(receipt)>4*1024*1024)throw new IllegalStateException("D028 verified stage receipt escaped its run evidence root");
        JsonNode proof=JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(receipt, 4 * 1024 * 1024, () -> new IllegalStateException("D028 verified stage receipt escaped its run evidence root")));var p=verified.prepared();
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(p.runId());
        if(!"margin_all".equals(proof.path("dataset").asText())||!proof.path("sourceComplete").asBoolean(false)
                ||proof.path("dedup").asBoolean(true)||!p.runId().equals(proof.path("runId").asText())
                ||!p.target().equals(proof.path("target").asText())||!p.stage().equals(proof.path("stage").asText())
                ||!p.requestFingerprint().equals(proof.path("requestFingerprint").asText())
                ||!SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId()).equals(p.requestFingerprint())
                ||proof.path("sourceRows").asInt(-1)!=verified.authoritativeRows().size()
                ||!MarginAllRows.sameRows(verified.authoritativeRows(),tables.open(p.stage()).window(p.from(),p.to()).rows()))
            throw new IllegalStateException("D028 publication source proof differs from frozen request/staged window");
    }
    private void verifyJournalReceipt(ReferencePublicationJournal.Entry entry,Layout layout)throws Exception {
        JsonNode scope=JobDefinitionJson.mapper().readTree(entry.intent().scope());String raw=scope.path("stageReceipt").asText("");
        Path receipt=Path.of(raw).toAbsolutePath().normalize();Path root=ledgerPath.getParent().resolve("sync-evidence").resolve(entry.intent().runId()).toAbsolutePath().normalize();
        if(!receipt.startsWith(root)||Files.isSymbolicLink(receipt)||!Files.isRegularFile(receipt)||Files.size(receipt)>4*1024*1024
                ||!sha(FileEvidenceStore.readBounded(receipt, 4 * 1024 * 1024, () -> new IllegalStateException("D028 verified stage receipt escaped its run evidence root"))).equals(scope.path("stageReceiptFingerprint").asText()))
            throw new IllegalStateException("D028 journal stage receipt hash/path is invalid");
        JsonNode proof=JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(receipt, 4 * 1024 * 1024, () -> new IllegalStateException("D028 verified stage receipt escaped its run evidence root")));
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(entry.intent().runId());
        String frozenFingerprint=SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId());
        if(!proof.path("sourceComplete").asBoolean(false)||proof.path("dedup").asBoolean(true)
                ||!entry.intent().runId().equals(proof.path("runId").asText())||!entry.intent().stage().equals(proof.path("stage").asText())
                ||!entry.intent().target().equals(proof.path("target").asText())
                ||!entry.intent().afterFingerprint().equals(proof.path("stageAfter").path("fingerprint").asText())
                ||!frozenFingerprint.equals(proof.path("requestFingerprint").asText())
                ||!frozenFingerprint.equals(scope.path("requestFingerprint").asText()))
            throw new IllegalStateException("D028 publication journal and verified-stage receipt disagree");
        LocalDate from=LocalDate.parse(proof.path("windowFrom").asText()),to=LocalDate.parse(proof.path("windowTo").asText()),next=from;
        JsonNode refs=proof.path("sourceReceipts");if(!refs.isArray()||refs.size()!=java.time.temporal.ChronoUnit.DAYS.between(from,to)+1)
            throw new IllegalStateException("D028 publication receipt lacks one source proof per date");
        var sourceRows=new java.util.ArrayList<com.zoutrankil.data.domain.MarginAll>();Path runRoot=ledgerPath.getParent().resolve("sync-evidence").resolve(entry.intent().runId()).toRealPath();
        for(JsonNode ref:refs){LocalDate day=LocalDate.parse(ref.path("fromInclusive").asText());
            if(!day.equals(next)||!day.equals(LocalDate.parse(ref.path("toInclusive").asText())))throw new IllegalStateException("D028 journal source slices are not one-date contiguous");
            Path sourceReceipt=Path.of(ref.path("responseEvidence").asText()).toAbsolutePath().normalize();
            if(!sourceReceipt.startsWith(runRoot)||Files.isSymbolicLink(sourceReceipt)||!Files.isRegularFile(sourceReceipt,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("D028 journal source receipt escaped run evidence");
            var page=MarginAllSource.reopen(sourceReceipt,ref.path("sourceFingerprint").asText(),day);
            if(page.rows().size()!=ref.path("rows").asInt(-1))throw new IllegalStateException("D028 journal source receipt row count differs");
            sourceRows.addAll(page.rows());next=next.plusDays(1);
        }
        if(!next.equals(to.plusDays(1))||sourceRows.size()!=proof.path("sourceRows").asInt(-1))throw new IllegalStateException("D028 journal raw-source evidence is incomplete");
        String replacement=layout==Layout.PUBLISHED?entry.intent().target():entry.intent().stage();
        if(!MarginAllRows.sameRows(sourceRows,tables.open(replacement).window(from,to).rows()))
            throw new IllegalStateException("D028 staged/published window differs from its reopened source receipts");
    }
    private void rename(String source,String target){
        if(exists(target))throw new IllegalStateException("D028 publication destination already exists: "+target);
        if(!exists(source))throw new IllegalStateException("D028 publication source table is absent: "+source);
        tables.rename(source,target);
    }
    private LockHolder acquireLock()throws Exception {
        Path path=ledgerPath.resolveSibling(ledgerPath.getFileName()+".d028-margin-all.lock");Files.createDirectories(path.getParent());
        var channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock;
        try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){channel.close();throw new IllegalStateException("D028 publication is already active",busy);}
        if(lock==null){channel.close();throw new IllegalStateException("D028 publication is already active");}return new LockHolder(channel,lock);
    }
    private static String sha(byte[] bytes)throws Exception{return FileEvidenceStore.sha256(bytes);}
    private static boolean same(MarginAllState.Snapshot a,MarginAllState.Snapshot b){return MarginAllRows.sameContent(a,b)
            &&a.identity().id()==b.identity().id()&&a.identity().directory().equals(b.identity().directory())&&a.identity().writerTxn()==b.identity().writerTxn();}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("D028 table publication cancelled");}
    private static final class LockHolder implements AutoCloseable {
        private final FileChannel channel;private final FileLock lock;LockHolder(FileChannel channel,FileLock lock){this.channel=channel;this.lock=lock;}
        @Override public void close()throws IOException{try{lock.release();}finally{channel.close();}}
    }
}
