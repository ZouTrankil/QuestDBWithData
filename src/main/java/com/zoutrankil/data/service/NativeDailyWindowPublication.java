package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.repository.ReferencePublicationJournal.State;

/** Shared bounded native daily publisher. Reuses the durable publication journal and dataset lease.
 * A completely verified stage replaces the formal target; the original physical table is retained.
 */
public final class NativeDailyWindowPublication<R extends Record> {
    public record Result<R extends Record>(ReferencePublicationJournal.Entry publication,
            NativeDailyWindowSnapshot<R> published,NativeDailyWindowSnapshot<R> backup,Path evidence) {}
    private record Source<R extends Record>(Path file,String fingerprint,String fileSha,List<R> rows) {}
    private final NativeDailyWindowPublicationStorage storage;
    private final Path ledgerPath;
    private final String dataset;
    private final NativeDailyWindowWritePort<R> port;

    public NativeDailyWindowPublication(NativeDailyWindowPublicationStorage storage,Path ledgerPath,String dataset,NativeDailyWindowWritePort<R> port) {
        DatasetDefinition.identifier(dataset);this.dataset=dataset;this.port=Objects.requireNonNull(port);
        this.ledgerPath=ledgerPath.toAbsolutePath().normalize();this.storage=Objects.requireNonNull(storage);
    }
    /** Safe for a plan: no journal/table is created by this read-only pending check. */
    public void requireNoPendingPublication()throws Exception {
        storage.requireNoPendingPublication(ledgerPath,dataset);
    }
    public Optional<ReferencePublicationJournal.Entry> findForRun(String runId)throws Exception {
        return storage.findForRun(ledgerPath,dataset,runId);
    }
    /** Called after the runner has fully acknowledged and verified the sole source slice. */
    public Result<R> publish(String runId,IntervalLockStore.Lease lease,NativeDailyWindowSnapshot<R> before,
            LocalDate from,LocalDate to,List<R> windowRows,Map<String,?> sourceScope,BooleanSupplier cancelled)throws Exception {
        NativeDailyWindowWritePort.requireWindow(from,to);check(cancelled);requireNoPendingPublication();
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=ledger.getRun(runId);requireOwner(run,from,to,before.targetId());
        if(ledger.get(runId).state()!=SyncRunState.RUNNING)throw new IllegalStateException("Running native daily owner required");
        var scope=scope(from,to,sourceScope);var source=source(runId,scope,"java."+dataset,null,from,to);
        if(windowRows==null||!port.digest(source.rows()).equals(port.digest(windowRows)))throw new IllegalStateException("Native daily source rows differ from calculated rows");
        requireVerifiedSlice(ledger,runId,source.rows().size(),source.fingerprint());
        var stage=port.snapshot(port.stage());requireReplacement(before,stage,source.rows(),from,to);port.requireSame(before);
        var journal=new ReferencePublicationJournal(ledgerPath,dataset);journal.requireLease(lease,false);
        if(!lease.runId().equals(runId))throw new IllegalStateException("Publication lease belongs to another run");
        String backup=port.stagePrefix()+"_backup_"+UUID.randomUUID().toString().replace("-","");
        var intent=new ReferencePublicationJournal.Intent(dataset+"-publication-"+UUID.randomUUID(),dataset,runId,port.table(),backup,port.stage(),before.targetId(),
                before.tableId(),before.directory(),stage.tableId(),before.fingerprint(),stage.fingerprint(),JobDefinitionJson.mapper().writeValueAsString(scope));
        var entry=journal.create(intent);
        try {
            check(cancelled);storage.rename(intent.target(),intent.backup());entry=journal.advance(entry,State.OLD_MOVED);
            check(cancelled);storage.rename(intent.stage(),intent.target());entry=journal.advance(entry,State.PUBLISHED);
            var published=port.formalSnapshot();var retained=port.snapshot(backup);requirePublished(intent,published,retained);
            entry=journal.advance(entry,State.VERIFIED);
            Path evidence=writePublicationEvidence(source,intent,published,source.rows().size());
            return new Result<>(entry,published,retained,evidence);
        } catch(Exception failure) {retainUncertain(journal,runId,failure);throw failure;}
    }
    /** Explicit stopped-writer recovery; validates frozen scope, source SHA, full stage and backup. */
    public NativeDailyWindowSnapshot<R> finishInterrupted(String runId,boolean writerStopped,String expectedProducer,String expectedModelVersion)throws Exception {
        if(!writerStopped)throw new IllegalArgumentException("Explicit stopped-writer proof required");
        if(!("java."+dataset).equals(expectedProducer))throw new IllegalArgumentException("Exact native owner producer required");
        var readLedger=SyncRunLedger.openReadOnly(ledgerPath);var run=readLedger.getRun(runId);
        var found=findForRun(runId).orElseThrow(()->new IllegalStateException("No native daily publication for run"));var intent=found.intent();
        var scope=JobDefinitionJson.mapper().readTree(intent.scope());LocalDate from=LocalDate.parse(scope.path("from").asText()),to=LocalDate.parse(scope.path("to").asText());
        NativeDailyWindowWritePort.requireWindow(from,to);requireOwner(run,from,to,intent.initialTarget());
        if(!port.table().equals(intent.target()))throw new IllegalStateException("Recovery formal target differs");
        var source=source(runId,JobDefinitionJson.mapper().convertValue(scope,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}),expectedProducer,expectedModelVersion,from,to);
        var slices=requireVerifiedSlice(readLedger,runId,source.rows().size(),source.fingerprint());
        var journal=new ReferencePublicationJournal(ledgerPath,dataset);var entry=journal.forRun(runId);IntervalLockStore locks=new SqliteIntervalLockStore(ledgerPath);
        var lease=locks.findOwned(runId,IntervalLockStore.Scope.allDates(dataset));
        boolean oldTarget=identityMatches(intent.target(),intent.originalId()),newTarget=identityMatches(intent.target(),intent.replacementId()),
                oldBackup=identityMatches(intent.backup(),intent.originalId()),newStage=identityMatches(intent.stage(),intent.replacementId());
        if(!(oldTarget&&newStage&&!tableExists(intent.backup())||!tableExists(intent.target())&&oldBackup&&newStage||newTarget&&oldBackup&&!tableExists(intent.stage())))
            throw new IllegalStateException("Recovery layout conflicts with durable publication identities");
        var original=port.snapshot(oldTarget?intent.target():intent.backup());var replacement=port.snapshot(newTarget?intent.target():intent.stage());
        if(original.tableId()!=intent.originalId()||!original.directory().equals(intent.originalDirectory())||!original.fingerprint().equals(intent.beforeFingerprint())
                ||replacement.tableId()!=intent.replacementId()||!replacement.fingerprint().equals(intent.afterFingerprint()))
            throw new IllegalStateException("Recovery content fingerprint or physical identity differs");
        requireReplacement(original,replacement,source.rows(),from,to);
        if(entry.state()!=State.VERIFIED) {
            if(lease==null)throw new IllegalStateException("Uncertain publication dataset lease missing");journal.requireLease(lease,true);
            if(entry.state()!=State.IN_DOUBT)entry=journal.advance(entry,State.IN_DOUBT);entry=journal.advance(entry,State.RESUMING);
            try {
                if(oldTarget)storage.rename(intent.target(),intent.backup());
                if(!newTarget)storage.rename(intent.stage(),intent.target());
                entry=journal.advance(entry,State.PUBLISHED);var actual=port.formalSnapshot();var backup=port.snapshot(intent.backup());requirePublished(intent,actual,backup);
                entry=journal.advance(entry,State.VERIFIED);
            } catch(Exception failure) {retainUncertain(journal,runId,failure);throw failure;}
        }
        var actual=port.formalSnapshot();var retained=port.snapshot(intent.backup());requirePublished(intent,actual,retained);
        writePublicationEvidence(source,intent,actual,source.rows().size());
        var ledger=new SyncRunLedger(ledgerPath);
        String proof=JobDefinitionJson.mapper().writeValueAsString(Map.of("sourceComplete",true,"returnedRows",source.rows().size(),"publication",intent.id(),
                "verification",Map.of("passed",true,"writerStopped",true,"expectedRows",source.rows().size(),"actualRows",source.rows().size(),"matchedRows",source.rows().size(),"duplicateKeys",0,"missingKeys",0,"mismatchedRows",0,"sourceFingerprint",source.fingerprint(),"readbackEvidence",intent.id())));
        for(var item:List.of(slices.getFirst().parentId(),runId)) {
            var current=ledger.get(item);if(current.state()==SyncRunState.VERIFIED)continue;
            if(current.state()==SyncRunState.RUNNING||current.state()==SyncRunState.ACKNOWLEDGED){ledger.transition(current.id(),current.revision(),SyncRunState.IN_DOUBT,"{\"publicationRecovery\":true}");current=ledger.get(current.id());}
            if(current.state()!=SyncRunState.IN_DOUBT)throw new IllegalStateException("Recovery ledger state cannot complete: "+current.state());ledger.transition(current.id(),current.revision(),SyncRunState.VERIFIED,proof);
        }
        if(lease!=null){if(!lease.inDoubt()){locks.retainInDoubt(lease);lease=locks.findOwned(runId,lease.scope());}locks.releaseAfterReconciliation(lease,true,true);}
        return actual;
    }
    private void requireOwner(SyncRunLedger.Run run,LocalDate from,LocalDate to,String target)throws Exception {
        var frozen=JobDefinitionJson.mapper().readTree(run.frozenJson());
        if(!run.jobId().equals("data."+dataset)||!run.targetId().equals(target)||!from.toString().equals(frozen.path("from").asText())||!to.toString().equals(frozen.path("to").asText()))
            throw new IllegalStateException("Frozen owner, target or daily window differs");
    }
    private Map<String,Object> scope(LocalDate from,LocalDate to,Map<String,?> sourceScope) {
        var scope=new LinkedHashMap<String,Object>(Objects.requireNonNull(sourceScope));
        for(var item:Map.of("from",from.toString(),"to",to.toString()).entrySet()) {
            Object old=scope.put(item.getKey(),item.getValue());if(old!=null&&!old.toString().equals(item.getValue()))throw new IllegalArgumentException("Publication scope window differs");
        }
        return scope;
    }
    private Source<R> source(String runId,Map<String,?> scope,String producer,String modelVersion,LocalDate from,LocalDate to)throws Exception {
        Object pathValue=scope.get("sourceEvidence"),shaValue=scope.get("sourceEvidenceSha256"),fingerprintValue=scope.get("sourceFingerprint");
        if(pathValue==null||shaValue==null||fingerprintValue==null||!shaValue.toString().matches("[0-9a-f]{64}")||!fingerprintValue.toString().matches("[0-9a-f]{64}"))
            throw new IllegalStateException("Complete owned source path and SHA evidence required");
        Path file=Path.of(pathValue.toString()).toAbsolutePath().normalize(),ownedRoot=ledgerPath.getParent().resolve("sync-evidence").resolve(runId).toAbsolutePath().normalize();
        if(!file.startsWith(ownedRoot)||!Files.isRegularFile(file)||Files.size(file)>4*1024*1024||!fileHash(file).equals(shaValue.toString()))
            throw new IllegalStateException("Source evidence missing, unowned or changed");
        var document=JobDefinitionJson.mapper().readTree(file.toFile());
        if(!producer.equals(document.path("producer").asText())||(modelVersion!=null&&!modelVersion.equals(document.path("modelVersion").asText()))
                ||!fingerprintValue.toString().equals(document.path("sourceFingerprint").asText())||!from.toString().equals(document.path("from").asText())
                ||!to.toString().equals(document.path("to").asText())||!document.path("rows").isArray())throw new IllegalStateException("Native source proof differs from publication scope");
        var rows=new ArrayList<R>();var seen=new HashSet<Instant>();
        for(var row:document.path("rows")) {
            R typed=JobDefinitionJson.mapper().treeToValue(row,port.rowType());
            if(port.outside(typed,from,to)||!seen.add(port.codec().key(typed)))throw new IllegalStateException("Source contains duplicate or out-of-window business date");rows.add(typed);
        }
        if(rows.isEmpty()||rows.size()>366)throw new IllegalStateException("Nonempty bounded native source rows required");
        rows.sort(Comparator.comparing(port.codec()::key));
        return new Source<>(file,fingerprintValue.toString(),shaValue.toString(),List.copyOf(rows));
    }
    private List<SyncRunLedger.Entry> requireVerifiedSlice(SyncRunLedger ledger,String runId,int rows,String fingerprint)throws Exception {
        var entries=ledger.entries(runId,null,100);var slices=entries.stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
        var attempts=entries.stream().filter(e->e.kind()==SyncRunLedger.Kind.ATTEMPT).toList();
        if(slices.size()!=1||attempts.size()!=1||!attempts.getFirst().id().equals(slices.getFirst().parentId())||slices.getFirst().state()!=SyncRunState.VERIFIED)
            throw new IllegalStateException("Exactly one fully verified native source slice required");
        var proof=JobDefinitionJson.mapper().readTree(slices.getFirst().payloadJson()).path("verification");
        if(!proof.path("passed").asBoolean()||proof.path("expectedRows").asInt(-1)!=rows||proof.path("actualRows").asInt(-1)!=rows||proof.path("matchedRows").asInt(-1)!=rows
                ||proof.path("missingKeys").asInt(-1)!=0||proof.path("duplicateKeys").asInt(-1)!=0||proof.path("mismatchedRows").asInt(-1)!=0||!fingerprint.equals(proof.path("sourceFingerprint").asText()))
            throw new IllegalStateException("Native source slice verification differs");
        return slices;
    }
    private void requireReplacement(NativeDailyWindowSnapshot<R> before,NativeDailyWindowSnapshot<R> stage,List<R> window,LocalDate from,LocalDate to) {
        var expected=new ArrayList<R>(before.rows().stream().filter(row->port.outside(row,from,to)).toList());expected.addAll(window);expected.sort(Comparator.comparing(port.codec()::key));
        if(!port.digest(expected).equals(stage.fingerprint())||expected.size()!=stage.rows().size())throw new IllegalStateException("Complete native stage differs from exact window replacement");
    }
    private void requirePublished(ReferencePublicationJournal.Intent intent,NativeDailyWindowSnapshot<R> published,NativeDailyWindowSnapshot<R> backup) {
        if(published.tableId()!=intent.replacementId()||!published.fingerprint().equals(intent.afterFingerprint())||backup.tableId()!=intent.originalId()
                ||!backup.directory().equals(intent.originalDirectory())||!backup.fingerprint().equals(intent.beforeFingerprint()))throw new IllegalStateException("Published target or retained backup differs");
    }
    private Path writePublicationEvidence(Source<R> source,ReferencePublicationJournal.Intent intent,NativeDailyWindowSnapshot<R> published,int windowRows)throws Exception {
        Path file=source.file().resolveSibling("publication.json");
        var body=Map.of("published",true,"backup",intent.backup(),"formalRows",published.rows().size(),"windowRows",windowRows,"fullTargetFingerprint",published.fingerprint(),"sourceFingerprint",source.fingerprint(),"sourceEvidence",source.file().toString());
        if(Files.isRegularFile(file)) {
            var existing=JobDefinitionJson.mapper().readTree(file.toFile());
            if(!existing.path("published").asBoolean()||!intent.backup().equals(existing.path("backup").asText())||!published.fingerprint().equals(existing.path("fullTargetFingerprint").asText())
                    ||!source.fingerprint().equals(existing.path("sourceFingerprint").asText())||existing.path("windowRows").asInt(-1)!=windowRows)
                throw new IllegalStateException("Existing publication evidence differs");
            return file;
        }
        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(body);
        try(var channel=java.nio.channels.FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        return file;
    }

    private void retainUncertain(ReferencePublicationJournal journal,String runId,Exception failure) {
        try {var entry=journal.forRun(runId);if(entry.state()!=State.VERIFIED&&entry.state()!=State.IN_DOUBT)journal.advance(entry,State.IN_DOUBT);}
        catch(Exception journalFailure){failure.addSuppressed(journalFailure);}
    }
    private boolean tableExists(String table){return storage.tableExists(table);}
    private boolean identityMatches(String table,long id) { return storage.identityMatches(table,id); }
    private static String fileHash(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private static void check(BooleanSupplier cancelled) {
        if(Objects.requireNonNull(cancelled).getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Native daily publication cancelled");
    }
}
