package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.MarginZrzStorage;
import com.zoutrankil.data.repository.MarginZrzStaging;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.SyncRunLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.repository.ReferencePublicationJournal.State;

/** D031 stage/backup rename journal; every generation retains YEAR/WAL/DEDUP=false. */
public final class MarginZrzPublication {
    public enum Layout { ORIGINAL, OLD_MOVED, PUBLISHED, CONFLICT }
    public record Result(ReferencePublicationJournal.Entry entry,Layout layout,
            MarginZrzStorage.Snapshot target,MarginZrzStorage.Snapshot backup) {}
    public static final class Uncertain extends Exception {
        private final String runId;
        public Uncertain(String runId,Exception cause){super("D031 table publication requires recovery: "+runId,cause);this.runId=runId;}
        public String runId(){return runId;}
    }
    public static final class Operation implements AutoCloseable {
        private final MarginZrzPublication owner;private final LockHolder lock;private boolean closed;
        private Operation(MarginZrzPublication owner,LockHolder lock){this.owner=owner;this.lock=lock;}
        @Override public synchronized void close()throws Exception{if(!closed){closed=true;lock.close();}}
    }
    private final JdbcTemplate jdbc;private final Path ledgerPath;private final ReferencePublicationJournal journal;
    public MarginZrzPublication(JdbcTemplate source,Path ledgerPath)throws Exception {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(120);
        this.ledgerPath=Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();new SyncRunLedger(this.ledgerPath);
        journal=new ReferencePublicationJournal(this.ledgerPath,"margin_zrz");
    }
    public Operation beginOperation()throws Exception{return beginOperation(null);}
    public Operation beginOperation(String exceptRun)throws Exception {
        LockHolder lock=acquireLock();try{requireNoPending(exceptRun);return new Operation(this,lock);}catch(Exception failure){lock.close();throw failure;}
    }
    public Result publish(Operation operation,MarginZrzStaging.Verified verified,BooleanSupplier cancelled)throws Exception {
        Objects.requireNonNull(verified);check(cancelled);
        if(operation==null||operation.owner!=this||operation.closed)throw new IllegalArgumentException("Active D031 publication lock required");
        var prepared=verified.prepared();requireNoPending(prepared.runId());
        var original=new MarginZrzStorage(jdbc,prepared.target()).snapshot();
        var stage=new MarginZrzStorage(jdbc,prepared.stage()).snapshot();
        if(!same(original,prepared.before())
                ||!MarginZrzStorage.physicalTargetId(jdbc,prepared.target(),original.identity()).equals(prepared.physicalTargetBefore())
                ||!same(stage,verified.snapshot())
                ||!MarginZrzStorage.physicalTargetId(jdbc,prepared.stage(),stage.identity()).equals(prepared.stagePhysicalTarget()))
            throw new IllegalStateException("D031 target or verified stage changed before publication");
        verifyStageReceipt(verified);
        String backup=com.zoutrankil.data.domain.MarginZrzDataset.ISOLATED_PREFIX+"backup_"+UUID.randomUUID().toString().replace("-","");
        String stageReceiptHash=sha(Files.readAllBytes(Path.of(verified.receipt())));
        String scope=JobDefinitionJson.mapper().writeValueAsString(java.util.Map.of("stageReceipt",verified.receipt(),
                "stageReceiptFingerprint",stageReceiptHash,"requestFingerprint",prepared.requestFingerprint(),
                "windowFrom",prepared.from(),"windowTo",prepared.to(),"sourceRows",verified.authoritativeRows().size()));
        var intent=new ReferencePublicationJournal.Intent("margin-zrz-publication-"+UUID.randomUUID(),"margin_zrz",
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
        if(!writerStopped)throw new IllegalStateException("D031 stopped-writer proof required before publication recovery");
        try(Operation operation=beginOperation(runId)){
            var entry=journal.forRun(runId);Layout layout=inspect(entry);if(layout==Layout.CONFLICT)throw new IllegalStateException("D031 table layout conflicts with durable journal identities/fingerprints");
            verifyJournalReceipt(entry,layout);
            if(entry.state()==State.VERIFIED){if(layout!=Layout.PUBLISHED)throw new IllegalStateException("Verified D031 publication drifted");return verifyPublished(entry);}
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
        if(!writerStopped)throw new IllegalStateException("D031 writer-stopped confirmation required");
        try(Operation operation=beginOperation(runId)){
            if(journal.findForRun(runId).isPresent())throw new IllegalStateException("D031 run has a publication journal; finish that publication instead of discarding its stage");
            var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=ledger.getRun(runId);var runEntry=ledger.get(runId);
            if(!MarginZrzSyncJobOwner.DEFINITION.jobId().equals(run.jobId())||run.jobVersion()!=MarginZrzSyncJobOwner.DEFINITION.version()
                    ||!MarginZrzTargetIdentity.logical(jdbc,target).equals(run.targetId())
                    ||!java.util.Set.of(SyncRunState.IN_DOUBT,SyncRunState.FAILED,SyncRunState.PARTIAL,SyncRunState.CANCELLED).contains(runEntry.state()))
                throw new IllegalStateException("D031 stage discard requires a stopped failed/cancelled/partial run for this logical target");
            Path evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
            com.zoutrankil.data.repository.MarginZrzStaging.discardUnpublished(jdbc,target,runId,evidence,ledgerPath,true);
            JsonNode frozen=JobDefinitionJson.mapper().readTree(run.frozenJson());
            LocalDate from=LocalDate.parse(frozen.path("from").asText()),to=LocalDate.parse(frozen.path("to").asText());
            var locks=new DatasetIntervalLock(ledgerPath);var scope=new DatasetIntervalLock.Scope("margin_zrz",from,to);var lease=locks.findOwned(runId,scope);
            if(lease!=null){if(!lease.inDoubt()){locks.retainInDoubt(lease);lease=locks.findOwned(runId,scope);}locks.releaseAfterReconciliation(lease,true,true);}
        }
    }
    public java.util.Optional<ReferencePublicationJournal.Entry> findForRun(String runId)throws Exception{return journal.findForRun(runId);}
    public void requireNoPendingPublication()throws Exception{requireNoPending(null);}
    private void requireNoPending(String exceptRun)throws Exception {
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+ledgerPath);var query=db.prepareStatement("SELECT run_id FROM reference_publications WHERE dataset='margin_zrz' AND state<>'VERIFIED' LIMIT 32");var rows=query.executeQuery()){
            while(rows.next())if(!Objects.equals(exceptRun,rows.getString(1)))throw new IllegalStateException("Unresolved D031 publication requires finish: "+rows.getString(1));
        }
        Path root=ledgerPath.getParent().resolve("sync-evidence");if(!Files.exists(root))return;
        if(Files.isSymbolicLink(root)||!Files.isDirectory(root))throw new IllegalStateException("D031 evidence root is invalid");
        int count=0;try(var runs=Files.newDirectoryStream(root)){for(Path dir:runs){if(++count>50_000)throw new IllegalStateException("D031 unresolved-stage scan exceeds 50000 runs");
            if(Files.isSymbolicLink(dir)||!Files.isDirectory(dir))continue;if(!MarginZrzStaging.hasStageIntent(dir))continue;
            String run=dir.getFileName().toString();if(Objects.equals(exceptRun,run))continue;
            var prior=journal.findForRun(run);if(prior.isPresent()&&prior.get().state()==State.VERIFIED)continue;
            throw new IllegalStateException("Unresolved D031 stage-only artifact requires explicit recovery: "+run);}}
    }
    private Layout inspect(ReferencePublicationJournal.Entry entry)throws Exception {
        var i=entry.intent();boolean target=exists(i.target()),backup=exists(i.backup()),stage=exists(i.stage());
        if(target&&backup&&!stage&&matches(i.target(),i.replacementId(),i.afterFingerprint())&&matches(i.backup(),i.originalId(),i.beforeFingerprint()))return Layout.PUBLISHED;
        if(!target&&backup&&stage&&matches(i.backup(),i.originalId(),i.beforeFingerprint())&&matches(i.stage(),i.replacementId(),i.afterFingerprint()))return Layout.OLD_MOVED;
        if(target&&!backup&&stage&&matches(i.target(),i.originalId(),i.beforeFingerprint())&&matches(i.stage(),i.replacementId(),i.afterFingerprint()))return Layout.ORIGINAL;
        return Layout.CONFLICT;
    }
    private Result verifyPublished(ReferencePublicationJournal.Entry entry)throws Exception {
        if(inspect(entry)!=Layout.PUBLISHED)throw new IllegalStateException("D031 published target differs from journal stage snapshot");
        return new Result(entry,Layout.PUBLISHED,new MarginZrzStorage(jdbc,entry.intent().target()).snapshot(),
                new MarginZrzStorage(jdbc,entry.intent().backup()).snapshot());
    }
    private boolean matches(String table,long id,String fingerprint)throws Exception {
        var snapshot=new MarginZrzStorage(jdbc,table).snapshot();return snapshot.identity().id()==id&&snapshot.fingerprint().equals(fingerprint);
    }
    private boolean exists(String table){return jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",table).size()==1;}
    private void verifyStageReceipt(MarginZrzStaging.Verified verified)throws Exception {
        Path receipt=Path.of(verified.receipt()).toRealPath();Path runRoot=verified.prepared().runEvidence().toRealPath();
        if(!receipt.startsWith(runRoot)||Files.size(receipt)<1||Files.size(receipt)>4*1024*1024)throw new IllegalStateException("D031 verified stage receipt escaped its run evidence root");
        JsonNode proof=JobDefinitionJson.mapper().readTree(Files.readAllBytes(receipt));var p=verified.prepared();
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(p.runId());
        if(!"margin_zrz".equals(proof.path("dataset").asText())||!proof.path("sourceComplete").asBoolean(false)
                ||proof.path("dedup").asBoolean(true)||!p.runId().equals(proof.path("runId").asText())
                ||!p.target().equals(proof.path("target").asText())||!p.stage().equals(proof.path("stage").asText())
                ||!p.requestFingerprint().equals(proof.path("requestFingerprint").asText())
                ||!SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId()).equals(p.requestFingerprint())
                ||proof.path("sourceRows").asInt(-1)!=verified.authoritativeRows().size()
                ||!MarginZrzStorage.sameRows(verified.authoritativeRows(),new MarginZrzStorage(jdbc,p.stage()).window(p.from(),p.to()).rows()))
            throw new IllegalStateException("D031 publication source proof differs from frozen request/staged window");
    }
    private void verifyJournalReceipt(ReferencePublicationJournal.Entry entry,Layout layout)throws Exception {
        JsonNode scope=JobDefinitionJson.mapper().readTree(entry.intent().scope());String raw=scope.path("stageReceipt").asText("");
        Path receipt=Path.of(raw).toAbsolutePath().normalize();Path root=ledgerPath.getParent().resolve("sync-evidence").resolve(entry.intent().runId()).toAbsolutePath().normalize();
        if(!receipt.startsWith(root)||Files.isSymbolicLink(receipt)||!Files.isRegularFile(receipt)||Files.size(receipt)>4*1024*1024
                ||!sha(Files.readAllBytes(receipt)).equals(scope.path("stageReceiptFingerprint").asText()))
            throw new IllegalStateException("D031 journal stage receipt hash/path is invalid");
        JsonNode proof=JobDefinitionJson.mapper().readTree(Files.readAllBytes(receipt));
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(entry.intent().runId());
        String frozenFingerprint=SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId());
        if(!proof.path("sourceComplete").asBoolean(false)||proof.path("dedup").asBoolean(true)
                ||!entry.intent().runId().equals(proof.path("runId").asText())||!entry.intent().stage().equals(proof.path("stage").asText())
                ||!entry.intent().target().equals(proof.path("target").asText())
                ||!entry.intent().afterFingerprint().equals(proof.path("stageAfter").path("fingerprint").asText())
                ||!frozenFingerprint.equals(proof.path("requestFingerprint").asText())
                ||!frozenFingerprint.equals(scope.path("requestFingerprint").asText()))
            throw new IllegalStateException("D031 publication journal and verified-stage receipt disagree");
        LocalDate from=LocalDate.parse(proof.path("windowFrom").asText()),to=LocalDate.parse(proof.path("windowTo").asText());
        JsonNode refs=proof.path("sourceReceipts");if(!refs.isArray()||refs.size()!=1)
            throw new IllegalStateException("D031 publication receipt requires one complete bounded range proof");
        JsonNode ref=refs.get(0);
        if(!from.toString().equals(ref.path("fromInclusive").asText())||!to.toString().equals(ref.path("toInclusive").asText()))
            throw new IllegalStateException("D031 journal source receipt range differs from frozen window");
        Path runRoot=ledgerPath.getParent().resolve("sync-evidence").resolve(entry.intent().runId()).toRealPath();
        Path sourceReceipt=Path.of(ref.path("responseEvidence").asText()).toAbsolutePath().normalize();
        if(!sourceReceipt.startsWith(runRoot)||Files.isSymbolicLink(sourceReceipt)||!Files.isRegularFile(sourceReceipt,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("D031 journal source receipt escaped run evidence");
        var sourcePage=MarginZrzSource.reopen(sourceReceipt,ref.path("sourceFingerprint").asText(),from,to);
        var sourceRows=sourcePage.rows();
        if(sourceRows.isEmpty()||sourceRows.size()!=ref.path("rows").asInt(-1)||sourceRows.size()!=proof.path("sourceRows").asInt(-1))
            throw new IllegalStateException("D031 journal raw-source evidence is incomplete or empty");
        String replacement=layout==Layout.PUBLISHED?entry.intent().target():entry.intent().stage();
        if(!MarginZrzStorage.sameRows(sourceRows,new MarginZrzStorage(jdbc,replacement).window(from,to).rows()))
            throw new IllegalStateException("D031 staged/published window differs from its reopened source receipts");
    }
    private void rename(String source,String target){
        if(exists(target))throw new IllegalStateException("D031 publication destination already exists: "+target);
        if(!exists(source))throw new IllegalStateException("D031 publication source table is absent: "+source);
        jdbc.execute("RENAME TABLE \""+source+"\" TO \""+target+"\"");
    }
    private LockHolder acquireLock()throws Exception {
        Path path=ledgerPath.resolveSibling(ledgerPath.getFileName()+".d031-margin-zrz.lock");Files.createDirectories(path.getParent());
        var channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock;
        try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){channel.close();throw new IllegalStateException("D031 publication is already active",busy);}
        if(lock==null){channel.close();throw new IllegalStateException("D031 publication is already active");}return new LockHolder(channel,lock);
    }
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static boolean same(MarginZrzStorage.Snapshot a,MarginZrzStorage.Snapshot b){return MarginZrzStorage.sameContent(a,b)
            &&a.identity().id()==b.identity().id()&&a.identity().directory().equals(b.identity().directory())&&a.identity().writerTxn()==b.identity().writerTxn();}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("D031 table publication cancelled");}
    private static final class LockHolder implements AutoCloseable {
        private final FileChannel channel;private final FileLock lock;LockHolder(FileChannel channel,FileLock lock){this.channel=channel;this.lock=lock;}
        @Override public void close()throws IOException{try{lock.release();}finally{channel.close();}}
    }
}
