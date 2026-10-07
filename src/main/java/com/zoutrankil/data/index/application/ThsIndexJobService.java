package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.port.ThsIndexTarget;

import com.zoutrankil.data.index.domain.ThsIndexState;
import com.zoutrankil.data.index.domain.policy.ThsIndexMerge;


import com.zoutrankil.data.index.mapper.ThsIndexMapper;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Single complete THS directory observation; no scoped response can replace a full catalog. */
@org.springframework.stereotype.Service
public final class ThsIndexJobService implements SyncJobOwner {
    public record Result(String runId,SyncRunState state,int sourceRows,int inserted,int revised,int removed,
                         int unchanged,int verifiedRows,String publicationId,String evidence,String errorCode) {}
    private final ThsIndexTarget targetAccess;
    private final TusharePageService pages;
    private final Path path;
    private final String table;
    @org.springframework.beans.factory.annotation.Autowired
    public ThsIndexJobService(ThsIndexTarget targetAccess,TusharePageService pages,
            @org.springframework.beans.factory.annotation.Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String path) {
        this(targetAccess,pages,Path.of(path));
    }
    public ThsIndexJobService(ThsIndexTarget targetAccess,TusharePageService pages,Path path) {
        this.targetAccess=Objects.requireNonNull(targetAccess);this.pages=Objects.requireNonNull(pages);
        this.path=path.toAbsolutePath().normalize();
        DatasetDefinition.identifier(targetAccess.tableName());this.table=targetAccess.tableName();
    }
    public static SyncJobDefinition definition() {
        return new SyncJobDefinition("data.ths_index",1,"ths_index",1,"ths_index_owner",
                Set.of(Mode.INCREMENTAL),Mode.INCREMENTAL,Map.of(),"tushare.shared","ths_index.complete",
                "questdb.full_key_values",new RetryPolicy(3,Duration.ofSeconds(1),Duration.ofMinutes(2)),
                Duration.ofMinutes(10),new Budget(1,1,1,5000,8*1024*1024),0,List.of(),Frequency.MONTHLY,
                ZoneId.of("Asia/Shanghai"),true,false);
    }
    public String datasetId() { return "ths_index"; }
    public Set<Mode> supportedSyncModes() { return definition().supportedModes(); }
    public List<SyncJobDefinition> syncJobDefinitions() { return List.of(definition()); }
    public String targetId() {
        var identity=targetAccess.open(table).preflight();
        return targetAccess.identify(table,identity.id(),identity.directory());
    }
    public FrozenRequest plan(LocalDate logicalDate) {
        return definition().freeze(null,Map.of(),logicalDate,logicalDate,logicalDate);
    }
    private static void validate(FrozenRequest request) {
        if(!request.definition().equals(definition()) || request.mode()!=Mode.INCREMENTAL
                || request.from()==null || !request.from().equals(request.to())
                || !request.from().equals(request.logicalDate()) || !request.parameters().isEmpty())
            throw new IllegalArgumentException("Frozen complete THS observation day required");
    }
    private record PreparedInput(List<ThsIndex> rows,String receipt,String fingerprint) {
        private PreparedInput { rows=List.copyOf(rows);Objects.requireNonNull(receipt);Objects.requireNonNull(fingerprint); }
    }
    public Result executePrepared(String run,String parent,FrozenRequest request,List<ThsIndex> rows,String receipt)
            throws Exception {
        validatePrepared(request);
        var mapper=new com.zoutrankil.data.index.mapper.ThsIndexMapper();
        var batch=DatasetWritePreparation.prepareWalReplace(ThsIndexDataset.DEFINITION,rows,mapper::values,
                new DatasetWritePreparation.Limits(ThsIndexState.MAX_ROWS,ThsIndexState.MAX_BYTES));
        var fingerprint=(String)request.parameters().get("payloadFingerprint");
        if(!batch.fingerprint().equals(fingerprint))
            throw new IllegalArgumentException("Prepared THS rows differ from frozen fingerprint");
        Path input=Path.of(receipt);if(Files.size(input)>ThsIndexState.MAX_BYTES)
            throw new IllegalArgumentException("Prepared THS receipt exceeds byte bound");
        var proof=JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(input,
                ThsIndexState.MAX_BYTES, () -> new IllegalArgumentException("Prepared THS receipt exceeds byte bound")));
        if(!proof.path("sourceKind").asText().equals("prepared-write-request")
                || !proof.path("fingerprint").asText().equals(fingerprint)
                || !proof.path("targetId").asText().equals(targetId())
                || !JobDefinitionJson.mapper().valueToTree(batch.rows()).equals(proof.path("rows")))
            throw new IllegalArgumentException("Prepared THS input differs from receipt");
        return executeInternal(run,parent,request,new PreparedInput(rows,receipt,fingerprint));
    }
    private static void validatePrepared(FrozenRequest request) {
        if(!request.definition().jobId().equals("write.ths_index") || request.definition().version()!=1
                || !request.definition().datasetId().equals("ths_index")
                || request.definition().datasetVersion()!=ThsIndexDataset.DEFINITION.schemaVersion()
                || request.mode()!=Mode.INGEST || request.from()!=null || request.to()!=null
                || !(request.parameters().get("payloadFingerprint") instanceof String hash)
                || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Prepared THS write job contract required");
    }
    public Result run(FrozenRequest request) throws Exception { return execute("ths-index-"+UUID.randomUUID(),null,request); }
    public Result finishInterrupted(String run,boolean writerStopped) throws Exception {
        return ThsIndexRunRecovery.finish(targetAccess,path,table,run,writerStopped);
    }
    public Result resume(FrozenRequest request,String priorRun) throws Exception {
        validate(request);var ledger=SyncRunLedger.openReadOnly(path);var prior=ledger.getRun(priorRun);
        if(!Set.of(SyncRunState.FAILED,SyncRunState.CANCELLED).contains(ledger.get(priorRun).state())
                || !prior.jobId().equals(definition().jobId()) || prior.jobVersion()!=definition().version()
                || !prior.frozenJson().equals(SyncRequestIdentity.snapshotJson(request)) || !prior.targetId().equals(targetId())
                || new DatasetIntervalLock(path).findOwned(priorRun,DatasetIntervalLock.Scope.allDates(datasetId()))!=null)
            throw new IllegalStateException("Reconcile uncertain THS writes before replaying the source");
        String next="ths-index-"+UUID.randomUUID();
        Path folder=path.getParent().resolve("sync-evidence").resolve(next);Files.createDirectories(folder);
        FileEvidenceStore.writeNewUtf8(folder.resolve("resume-intent.json"),JobDefinitionJson.mapper().writeValueAsString(
                Map.of("priorRunId",priorRun,"request",prior.frozenJson(),"targetId",prior.targetId())));
        return execute(next,null,request);
    }
    public SyncJobRunner.Result runAsGroupChild(String child,String parent,String expectedTarget,FrozenRequest request) throws Exception {
        if(!targetId().equals(expectedTarget)) throw new IllegalStateException("THS group target changed before execution");
        var result=execute(child,parent,request);
        return new SyncJobRunner.Result(result.runId(),result.state(),result.sourceRows(),result.verifiedRows(),result.errorCode());
    }
    public String revalidateGroupChild(String child,String expectedTarget,FrozenRequest request) throws Exception {
        if(request.definition().jobId().equals("write.ths_index")) validatePrepared(request);else validate(request);
        var ledger=SyncRunLedger.openReadOnly(path);var prior=ledger.getRun(child);
        if(ledger.get(child).state()!=SyncRunState.VERIFIED || !prior.targetId().equals(expectedTarget)
                || !prior.frozenJson().equals(SyncRequestIdentity.snapshotJson(request)))
            throw new IllegalStateException("Prior THS child differs from frozen member");
        var folder=path.getParent().resolve("sync-evidence").resolve(child);var json=JobDefinitionJson.mapper();
        var prepared=json.readTree(folder.resolve("prepared.json").toFile());
        var receipt=folder.resolve("completion.json");var proof=json.readTree(receipt.toFile());
        if(!prepared.path("runId").asText().equals(child) || !prepared.path("targetId").asText().equals(expectedTarget)
                || !prepared.path("request").asText().equals(prior.frozenJson()) || !proof.path("runId").asText().equals(child))
            throw new IllegalStateException("THS completion differs from frozen run");
        var before=json.treeToValue(proof.path("before"),ThsIndexState.Snapshot.class);
        var saved=json.treeToValue(proof.path("actual"),ThsIndexState.Snapshot.class);
        var actual=targetAccess.open(table).snapshot();
        if(!expectedTarget.equals(targetAccess.identify(table,before.identity().id(),before.identity().directory()))
                || !saved.equals(actual)) throw new IllegalStateException("Completed THS target drifted");
        return receipt.toString();
    }
    public Result execute(String run,String parent,FrozenRequest request) throws Exception {
        return executeInternal(run,parent,request,null);
    }
    private Result executeInternal(String run,String parent,FrozenRequest request,PreparedInput input) throws Exception {
        if(input==null) validate(request);
        String target=targetId();var storage=targetAccess.open(table);
        var ledger=new SyncRunLedger(path);var locks=new DatasetIntervalLock(path);ledger.createRun(run,parent,target,request);
        var folder=path.getParent().resolve("sync-evidence").resolve(run);
        var lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates(datasetId()));
        if(lease==null) {
            move(ledger,run,SyncRunState.FAILED,Map.of("errorCode","DATASET_INTERVAL_BUSY"));
            return new Result(run,SyncRunState.FAILED,0,0,0,0,0,0,null,folder.toString(),"DATASET_INTERVAL_BUSY");
        }
        String attempt=run+"-attempt",slice=run+"-snapshot";var entries=new ArrayList<String>();entries.add(run);
        long started=System.nanoTime();
        BooleanSupplier cancelled=()-> {
            if(Thread.currentThread().isInterrupted() || System.nanoTime()-started>request.definition().timeout().toNanos()) return true;
            try { return ledger.cancellationRequested(run) || parent!=null && ledger.cancellationRequested(parent); }
            catch(java.sql.SQLException failure) { throw new IllegalStateException("Cannot read cancellation",failure); }
        };
        boolean submitted=false;int sourceCount=0;String publication=null;
        try {
            move(ledger,run,SyncRunState.RUNNING,Map.of());
            ledger.createChild(attempt,SyncRunLedger.Kind.ATTEMPT,run,run);entries.addFirst(attempt);
            move(ledger,attempt,SyncRunState.RUNNING,Map.of());
            ledger.createChild(slice,SyncRunLedger.Kind.SLICE,run,attempt);entries.addFirst(slice);
            move(ledger,slice,SyncRunState.RUNNING,Map.of("unit","complete THS directory"));
            check(cancelled);Files.createDirectories(folder);
            SyncJobRunner.Page<ThsIndex> source;
            if(input==null) source=new ThsIndexSource(pages,folder).fetch(ThsIndexState.Scope.all(),
                    Instant.now().truncatedTo(ChronoUnit.MICROS),cancelled);
            else {
                Path receipt=Path.of(input.receipt()).toAbsolutePath().normalize();
                if(Files.size(receipt)>ThsIndexState.MAX_BYTES) throw new IllegalStateException("Prepared THS receipt exceeds bound");
                byte[] bytes=FileEvidenceStore.readBounded(receipt, ThsIndexState.MAX_BYTES, () -> new IllegalStateException("Prepared THS receipt exceeds bound"));var proof=JobDefinitionJson.mapper().readTree(bytes);
                var mapper=new com.zoutrankil.data.index.mapper.ThsIndexMapper();
                if(!proof.path("sourceKind").asText().equals("prepared-write-request")
                        || !proof.path("targetId").asText().equals(target)
                        || !proof.path("fingerprint").asText().equals(input.fingerprint())
                        || !JobDefinitionJson.mapper().valueToTree(input.rows().stream().map(mapper::values).toList())
                                .equals(proof.path("rows")))
                    throw new IllegalStateException("Prepared THS receipt changed after admission");
                String hash=FileEvidenceStore.sha256(bytes);
                source=new SyncJobRunner.Page<>(input.rows(),hash,receipt.toString(),null);
            }
            sourceCount=source.rows().size();if(sourceCount==0) throw new IllegalStateException("Empty THS source cannot certify completeness");
            var before=storage.snapshot();
            if(!target.equals(targetAccess.identify(table,before.identity().id(),before.identity().directory())))
                throw new IllegalStateException("THS target changed after run creation");
            var prepared=input==null?targetAccess.prepare(before,source.rows(),ThsIndexState.Scope.all())
                    :targetAccess.preparePrepared(before,source.rows());var merge=prepared.merge();
            FileEvidenceStore.writeNewUtf8(folder.resolve("prepared.json"),JobDefinitionJson.mapper().writeValueAsString(Map.of(
                    "runId",run,"targetId",target,"request",SyncRequestIdentity.snapshotJson(request),
                    "source",source,"prepared",prepared)));
            check(cancelled);
            if(merge.requiresWrite()) {
                submitted=true;
                var stage=targetAccess.newStaging().write(prepared,folder,cancelled);
                publication=new ThsIndexPublication(targetAccess.publicationTables(),path).publish(lease,table,prepared,stage,cancelled).publication().intent().id();
            }
            var actual=storage.snapshot();
            if(!actual.rows().equals(prepared.rows())) throw new IllegalStateException("Final THS catalog differs from merged input");
            var indexed=new HashMap<String,ThsIndex>();actual.businessRows().forEach(row->indexed.put(row.tsCode(),row));
            for(var row:source.rows()) if(!ThsIndexMerge.sameBusinessValues(row,indexed.get(row.tsCode())))
                throw new IllegalStateException("Final THS source-key value mismatch");
            Path receipt=folder.resolve("completion.json");var proof=new LinkedHashMap<String,Object>();
            proof.put("runId",run);proof.put("source",source);proof.put("before",before);proof.put("actual",actual);
            proof.put("merge",merge);proof.put("publicationId",publication);
            proof.put("submittedStageRows",merge.requiresWrite()?prepared.rows().size():0);
            FileEvidenceStore.writeNewUtf8(receipt,JobDefinitionJson.mapper().writeValueAsString(proof));
            var verification=Map.of("passed",true,"expectedRows",sourceCount,"actualRows",sourceCount,
                    "matchedRows",sourceCount,"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,
                    "readbackEvidence",receipt.toString(),"sourceFingerprint",source.sourceFingerprint(),"writerStopped",true);
            var payload=Map.of("evidence",receipt.toString(),"checkpoint",actual.fingerprint(),"verification",verification);
            for(String entry:entries) move(ledger,entry,SyncRunState.VERIFIED,payload);
            String releaseError=null;
            try { locks.releaseVerified(lease); } catch(RuntimeException failure) { releaseError="VERIFIED_LOCK_RELEASE_PENDING"; }
            return new Result(run,SyncRunState.VERIFIED,sourceCount,merge.inserted(),merge.revised(),merge.removed(),
                    merge.unchanged(),sourceCount,publication,receipt.toString(),releaseError);
        } catch(Exception failure) {
            var state=submitted?SyncRunState.IN_DOUBT:(failure instanceof java.util.concurrent.CancellationException || cancelled.getAsBoolean())
                    ?SyncRunState.CANCELLED:SyncRunState.FAILED;
            var payload=Map.of("errorCode",failure.getClass().getSimpleName(),"sourceRows",sourceCount,"evidence",folder.toString());
            for(String entry:entries) if(!ledger.get(entry).state().terminal()) move(ledger,entry,state,payload);
            var actual=locks.findOwned(run,lease.scope());
            if(submitted) { if(actual!=null && !actual.inDoubt()) locks.retainInDoubt(actual); }
            else locks.releaseVerified(lease);
            return new Result(run,state,sourceCount,0,0,0,0,0,publication,folder.toString(),failure.getClass().getSimpleName());
        }
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("THS job cancelled or timed out");
    }
    private static void move(SyncRunLedger ledger,String id,SyncRunState state,Map<String,?> payload) throws Exception {
        ledger.transition(id,ledger.get(id).revision(),state,JobDefinitionJson.mapper().writeValueAsString(payload));
    }
}
