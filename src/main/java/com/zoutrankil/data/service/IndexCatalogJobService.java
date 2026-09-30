package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** File identity is frozen; one verified merged publication is the resumable dataset unit. */
@org.springframework.stereotype.Service
public final class IndexCatalogJobService implements SyncJobOwner {
    public record Result(String runId,SyncRunState state,int sourceRows,int inserted,int revised,int unchanged,
                         int retainedAbsent,int verifiedRows,String publicationId,String evidence,String errorCode) {}
    private final JdbcTemplate jdbc;
    private final Path path;
    private final String table;
    @org.springframework.beans.factory.annotation.Autowired
    public IndexCatalogJobService(JdbcTemplate jdbc,
            @org.springframework.beans.factory.annotation.Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String path,
            @org.springframework.beans.factory.annotation.Value("${app.sync.index-catalog-table:index}") String table) {
        this(jdbc,Path.of(path),table);
    }
    public IndexCatalogJobService(JdbcTemplate jdbc,Path path,String table) {
        this.jdbc=Objects.requireNonNull(jdbc);this.path=path.toAbsolutePath().normalize();
        DatasetDefinition.identifier(table);this.table=table;
    }
    public static SyncJobDefinition definition() {
        return new SyncJobDefinition("data.index",1,"index",1,"index_catalog_owner",
                Set.of(Mode.INCREMENTAL,Mode.INGEST),Mode.INCREMENTAL,
                Map.of("file",new Parameter(ParameterType.STRING,true,4096,1,Set.of()),
                        "sha256",new Parameter(ParameterType.STRING,true,64,1,Set.of())),
                "file.bounded","index_catalog.file","questdb.full_key_values",
                new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofSeconds(1)),Duration.ofMinutes(20),
                new Budget(1,1,20,5000,8*1024*1024),0,List.of(),Frequency.MANUAL,ZoneId.of("Asia/Shanghai"),true,false);
    }
    public String datasetId() { return "index"; }
    public Set<Mode> supportedSyncModes() { return definition().supportedModes(); }
    public List<SyncJobDefinition> syncJobDefinitions() { return List.of(definition()); }
    public String targetId() {
        var identity=new IndexCatalogStorage(jdbc,table).preflight();
        return StaticTargetIdentity.identify(jdbc,table,identity.id(),identity.directory());
    }
    public FrozenRequest plan(Path file,LocalDate logicalDate) throws Exception {
        var source=new IndexCatalogFileSource().read(file,Instant.EPOCH,()->false);
        return definition().freeze(null,Map.of("file",source.path(),"sha256",source.sha256()),logicalDate,logicalDate,logicalDate);
    }
    private static void validate(FrozenRequest request) {
        if(!request.definition().equals(definition()) || request.from()==null || request.to()==null
                || !request.from().equals(request.logicalDate()) || !request.to().equals(request.logicalDate())
                || !Path.of((String)request.parameters().get("file")).isAbsolute()
                || !((String)request.parameters().get("sha256")).matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen absolute file/hash and observation day required");
    }
    private record PreparedInput(List<IndexCatalogEntry> rows,String receipt,String fingerprint) {
        private PreparedInput { rows=List.copyOf(rows);Objects.requireNonNull(receipt);Objects.requireNonNull(fingerprint); }
    }
    public Result executePrepared(String run,String parent,FrozenRequest request,
                                  List<IndexCatalogEntry> rows,String receipt) throws Exception {
        validatePrepared(request);
        var mapper=new com.zoutrankil.data.mapper.IndexCatalogMapper();
        var batch=DatasetWritePreparation.prepareWalReplace(IndexCatalogDataset.DEFINITION,rows,mapper::values,
                new DatasetWritePreparation.Limits(IndexCatalogFileSource.MAX_ROWS,16*1024*1024));
        var fingerprint=(String)request.parameters().get("payloadFingerprint");
        if(!batch.fingerprint().equals(fingerprint))
            throw new IllegalArgumentException("Prepared catalog rows differ from frozen fingerprint");
        var proof=JobDefinitionJson.mapper().readTree(IndexCatalogFileSource.readBounded(Path.of(receipt),16*1024*1024));
        if(!batch.fingerprint().equals(proof.path("fingerprint").asText())
                || !JobDefinitionJson.mapper().valueToTree(batch.rows()).equals(proof.path("rows")))
            throw new IllegalArgumentException("Prepared catalog input differs from source receipt");
        return executeInternal(run,parent,request,new PreparedInput(rows,receipt,fingerprint));
    }
    private static void validatePrepared(FrozenRequest request) {
        if(!request.definition().jobId().equals("write.index") || request.definition().version()!=1
                || !request.definition().datasetId().equals("index")
                || request.definition().datasetVersion()!=IndexCatalogDataset.DEFINITION.schemaVersion()
                || request.mode()!=Mode.INGEST || request.from()!=null || request.to()!=null
                || !(request.parameters().get("payloadFingerprint") instanceof String hash)
                || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Prepared catalog write job contract required");
    }
    public Result run(FrozenRequest request) throws Exception { return execute("index-catalog-"+UUID.randomUUID(),null,request); }
    public Result finishInterrupted(String run,boolean writerStopped) throws Exception {
        return IndexCatalogRunRecovery.finish(jdbc,path,table,run,writerStopped);
    }
    public SyncJobRunner.Result runAsGroupChild(String child,String parent,String expectedTarget,FrozenRequest request) throws Exception {
        if(!targetId().equals(expectedTarget)) throw new IllegalStateException("Catalog group target changed before execution");
        var result=execute(child,parent,request);
        return new SyncJobRunner.Result(result.runId(),result.state(),result.sourceRows(),result.verifiedRows(),result.errorCode());
    }
    public String revalidateGroupChild(String child,String expectedTarget,FrozenRequest request) throws Exception {
        if(request.definition().jobId().equals("write.index")) validatePrepared(request);else validate(request);
        var ledger=SyncRunLedger.openReadOnly(path);var prior=ledger.getRun(child);
        if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(ledger.get(child).state())
                || !prior.targetId().equals(expectedTarget) || !prior.frozenJson().equals(SyncRequestIdentity.snapshotJson(request)))
            throw new IllegalStateException("Prior catalog child differs from frozen member");
        var folder=path.getParent().resolve("sync-evidence").resolve(child);var json=JobDefinitionJson.mapper();
        var prepared=json.readTree(folder.resolve("prepared.json").toFile());
        Path receipt=folder.resolve("completion.json");var proof=json.readTree(receipt.toFile());
        if(!prepared.path("runId").asText().equals(child) || !prepared.path("request").asText().equals(prior.frozenJson())
                || !prepared.path("targetId").asText().equals(expectedTarget) || !proof.path("runId").asText().equals(child))
            throw new IllegalStateException("Catalog completion differs from frozen run");
        var before=json.treeToValue(proof.path("before"),IndexCatalogStorage.Snapshot.class);
        var saved=json.treeToValue(proof.path("actual"),IndexCatalogStorage.Snapshot.class);
        var actual=new IndexCatalogStorage(jdbc,table).snapshot();
        if(!expectedTarget.equals(StaticTargetIdentity.identify(jdbc,table,before.identity().id(),before.identity().directory()))
                || !saved.equals(actual)) throw new IllegalStateException("Completed catalog target drifted");
        return receipt.toString();
    }
    public Result resume(FrozenRequest request,String priorRun) throws Exception {
        validate(request);var ledger=SyncRunLedger.openReadOnly(path);var prior=ledger.getRun(priorRun);
        if(!Set.of(SyncRunState.FAILED,SyncRunState.CANCELLED).contains(ledger.get(priorRun).state())
                || !prior.jobId().equals(definition().jobId()) || prior.jobVersion()!=definition().version()
                || !prior.frozenJson().equals(SyncRequestIdentity.snapshotJson(request)) || !prior.targetId().equals(targetId())
                || new DatasetIntervalLock(path).findOwned(priorRun,DatasetIntervalLock.Scope.allDates(datasetId()))!=null)
            throw new IllegalStateException("Reconcile uncertain writes before replaying bounded catalog source");
        String next="index-catalog-"+UUID.randomUUID();
        Path folder=path.getParent().resolve("sync-evidence").resolve(next);Files.createDirectories(folder);
        Files.writeString(folder.resolve("resume-intent.json"),JobDefinitionJson.mapper().writeValueAsString(
                Map.of("priorRunId",priorRun,"request",prior.frozenJson(),"targetId",prior.targetId())),StandardOpenOption.CREATE_NEW);
        return execute(next,null,request);
    }
    public Result execute(String run,String parent,FrozenRequest request) throws Exception {
        return executeInternal(run,parent,request,null);
    }
    private Result executeInternal(String run,String parent,FrozenRequest request,PreparedInput input) throws Exception {
        if(input==null) validate(request);
        String target=targetId();var storage=new IndexCatalogStorage(jdbc,table);
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
            ledger.createChild(attempt,SyncRunLedger.Kind.ATTEMPT,run,run);entries.addFirst(attempt);move(ledger,attempt,SyncRunState.RUNNING,Map.of());
            ledger.createChild(slice,SyncRunLedger.Kind.SLICE,run,attempt);entries.addFirst(slice);move(ledger,slice,SyncRunState.RUNNING,Map.of("unit","complete merged catalog"));
            check(cancelled);IndexCatalogFileSource.Input source;
            if(input==null) {
                source=new IndexCatalogFileSource().read(Path.of((String)request.parameters().get("file")),
                        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),cancelled);
                if(!source.sha256().equals(request.parameters().get("sha256")))
                    throw new IllegalStateException("Source file changed after planning");
            } else {
                Path receipt=Path.of(input.receipt()).toAbsolutePath().normalize();
                byte[] bytes=IndexCatalogFileSource.readBounded(receipt,16*1024*1024);
                var proof=JobDefinitionJson.mapper().readTree(bytes);
                var mapper=new com.zoutrankil.data.mapper.IndexCatalogMapper();
                if(!proof.path("sourceKind").asText().equals("prepared-write-request")
                        || !proof.path("targetId").asText().equals(target)
                        || !proof.path("fingerprint").asText().equals(input.fingerprint())
                        || !JobDefinitionJson.mapper().valueToTree(input.rows().stream().map(mapper::values).toList())
                                .equals(proof.path("rows")))
                    throw new IllegalStateException("Prepared catalog receipt changed after admission");
                String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                source=new IndexCatalogFileSource.Input(receipt.toString(),hash,bytes.length,input.rows());
            }
            sourceCount=source.rows().size();var before=storage.snapshot();
            if(!target.equals(StaticTargetIdentity.identify(jdbc,table,before.identity().id(),before.identity().directory())))
                throw new IllegalStateException("Target changed after run creation");
            var prepared=IndexCatalogStaging.prepare(before,source.rows());var merge=prepared.merge();
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("prepared.json"),JobDefinitionJson.mapper().writeValueAsString(Map.of(
                    "runId",run,"targetId",target,"request",SyncRequestIdentity.snapshotJson(request),"source",source,"prepared",prepared)),StandardOpenOption.CREATE_NEW);
            check(cancelled);
            if(merge.requiresWrite()) {
                submitted=true;
                var stage=new IndexCatalogStaging(jdbc).write(prepared,folder,cancelled);
                publication=new IndexCatalogPublication(jdbc,path).publish(lease,table,prepared,stage,cancelled).publication().intent().id();
            }
            var actual=storage.snapshot();
            if(!actual.rows().equals(prepared.rows())) throw new IllegalStateException("Final catalog differs from merged input");
            var indexed=new HashMap<String,IndexCatalogEntry>();actual.businessRows().forEach(row->indexed.put(row.indexCode(),row));
            for(var row:source.rows()) if(!IndexCatalogMerge.sameBusinessValues(row,indexed.get(row.indexCode())))
                throw new IllegalStateException("Final catalog source-key value mismatch");
            Path receipt=folder.resolve("completion.json");var proof=new LinkedHashMap<String,Object>();
            proof.put("runId",run);proof.put("source",source);proof.put("before",before);proof.put("actual",actual);
            proof.put("merge",merge);proof.put("publicationId",publication);proof.put("submittedStageRows",merge.requiresWrite()?prepared.rows().size():0);
            Files.writeString(receipt,JobDefinitionJson.mapper().writeValueAsString(proof),StandardOpenOption.CREATE_NEW);
            var state=sourceCount==0?SyncRunState.VERIFIED_EMPTY:SyncRunState.VERIFIED;
            var payload=new LinkedHashMap<String,Object>();payload.put("evidence",receipt.toString());payload.put("checkpoint",actual.fingerprint());
            if(sourceCount==0) {
                payload.put("sourceComplete",true);payload.put("returnedRows",0);payload.put("submittedRows",0);
                payload.put("responseEvidence",folder.resolve("prepared.json").toString());
            } else payload.put("verification",Map.of("passed",true,"expectedRows",sourceCount,"actualRows",sourceCount,
                    "matchedRows",sourceCount,"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,
                    "readbackEvidence",receipt.toString(),"sourceFingerprint",source.sha256(),"writerStopped",true));
            for(String entry:entries) move(ledger,entry,state,payload);
            String releaseError=null;
            try { locks.releaseVerified(lease); } catch(RuntimeException failure) { releaseError="VERIFIED_LOCK_RELEASE_PENDING"; }
            return new Result(run,state,sourceCount,merge.inserted(),merge.revised(),merge.unchanged(),merge.retainedAbsent(),sourceCount,
                    publication,receipt.toString(),releaseError);
        } catch(Exception failure) {
            var state=submitted?SyncRunState.IN_DOUBT:failure instanceof java.util.concurrent.CancellationException?SyncRunState.CANCELLED:SyncRunState.FAILED;
            var payload=Map.of("errorCode",failure.getClass().getSimpleName(),"sourceRows",sourceCount,"evidence",folder.toString());
            for(String entry:entries) if(!ledger.get(entry).state().terminal()) move(ledger,entry,state,payload);
            var actual=locks.findOwned(run,lease.scope());
            if(submitted) { if(actual!=null && !actual.inDoubt()) locks.retainInDoubt(actual); }
            else locks.releaseVerified(lease);
            return new Result(run,state,sourceCount,0,0,0,0,0,publication,folder.toString(),failure.getClass().getSimpleName());
        }
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Catalog job cancelled or timed out");
    }
    private static void move(SyncRunLedger ledger,String id,SyncRunState state,Map<String,?> payload) throws Exception {
        ledger.transition(id,ledger.get(id).revision(),state,JobDefinitionJson.mapper().writeValueAsString(payload));
    }
}
