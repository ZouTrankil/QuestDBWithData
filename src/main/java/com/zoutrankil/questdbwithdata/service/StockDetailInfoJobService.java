package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** Finite static-reference sync: source observation through publication under one durable whole-table lease. */
@org.springframework.stereotype.Service
public final class StockDetailInfoJobService implements SyncJobOwner {
    public record Result(String runId,SyncRunState state,int sourceRows,int insertedRows,int updatedRows,
                         int unchangedRows,int verifiedRows,String publicationId,String evidence,String errorCode) {}
    private final TusharePageService pages;
    private final JdbcTemplate jdbc;
    private final Path ledgerPath;
    private final String table;
    @org.springframework.beans.factory.annotation.Autowired
    public StockDetailInfoJobService(TusharePageService pages,JdbcTemplate jdbc,
            @org.springframework.beans.factory.annotation.Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            @org.springframework.beans.factory.annotation.Value("${app.sync.stock-detail-table:stock_detail_info}") String table) {
        this(pages,jdbc,Path.of(ledgerPath),table);
    }
    public StockDetailInfoJobService(TusharePageService pages,JdbcTemplate jdbc,Path ledgerPath,String table) {
        this.pages=Objects.requireNonNull(pages);this.jdbc=Objects.requireNonNull(jdbc);
        this.ledgerPath=ledgerPath.toAbsolutePath().normalize();DatasetDefinition.identifier(table);this.table=table;
    }
    public static SyncJobDefinition definition() {
        return new SyncJobDefinition("data.stock_detail_info",1,"stock_detail_info",1,"stock_detail_info_owner",
                Set.of(Mode.INCREMENTAL,Mode.RECONCILE),Mode.INCREMENTAL,
                Map.of("codes",new Parameter(ParameterType.STRING_LIST,false,12,20,Set.of()),
                        "discover",new Parameter(ParameterType.BOOLEAN,true,1,1,Set.of())),
                "tushare.shared","stock_detail_info.identity","questdb.full_key_values",
                new RetryPolicy(3,Duration.ofSeconds(1),Duration.ofMinutes(2)),Duration.ofMinutes(20),
                new Budget(1,60,60,10000,16*1024*1024),0,List.of(),Frequency.WEEKLY,ZoneId.of("Asia/Shanghai"),true,false);
    }
    public String datasetId() { return "stock_detail_info"; }
    public Set<Mode> supportedSyncModes() { return definition().supportedModes(); }
    public List<SyncJobDefinition> syncJobDefinitions() { return List.of(definition()); }
    public String targetId() {
        return StaticTargetIdentity.identify(jdbc,table,new StockDetailInfoStorage(jdbc,table).preflight());
    }
    public Result reconcilePublished(String runId,boolean writerStopped) throws Exception {
        return StockDetailInfoRunRecovery.reconcilePublished(jdbc,ledgerPath,table,runId,writerStopped);
    }
    public Result finishInterrupted(String runId,boolean writerStopped) throws Exception {
        return StockDetailInfoRunRecovery.finishInterrupted(jdbc,ledgerPath,table,runId,writerStopped);
    }
    public SyncJobRunner.Result runAsGroupChild(String childId,String parentId,String expectedTarget,
                                                FrozenRequest request) throws Exception {
        if(!targetId().equals(expectedTarget)) throw new IllegalStateException("Static group target changed before child run");
        var result=execute(childId,parentId,request);
        return new SyncJobRunner.Result(result.runId(),result.state(),result.sourceRows(),
                result.verifiedRows(),result.errorCode());
    }
    public String revalidateGroupChild(String priorChild,String expectedTarget,FrozenRequest request)
            throws Exception {
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=ledger.getRun(priorChild);
        if(ledger.get(priorChild).state()!=SyncRunState.VERIFIED
                || !run.jobId().equals(request.definition().jobId())
                || run.jobVersion()!=request.definition().version()
                || !run.targetId().equals(expectedTarget)
                || !run.frozenJson().equals(SyncRequestIdentity.snapshotJson(request)))
            throw new IllegalStateException("Prior static child differs from frozen group member");
        Path receipt=ledgerPath.getParent().resolve("sync-evidence").resolve(priorChild).resolve("completion.json");
        var json=JobDefinitionJson.mapper();var proof=json.readTree(receipt.toFile());
        if(!priorChild.equals(proof.path("runId").asText())
                || !run.frozenJson().equals(proof.path("request").asText()))
            throw new IllegalStateException("Static child completion differs from prior run");
        var actual=new StockDetailInfoStorage(jdbc,table).snapshot();
        var beforeIdentity=json.treeToValue(proof.path("beforeIdentity"),StockDetailInfoStorage.Identity.class);
        var afterIdentity=json.treeToValue(proof.path("after").path("identity"),StockDetailInfoStorage.Identity.class);
        if(!expectedTarget.equals(StaticTargetIdentity.identify(jdbc,table,beforeIdentity))
                || !actual.identity().equals(afterIdentity))
            throw new IllegalStateException("Static recovery endpoint or physical successor differs");
        if(!actual.fingerprint().equals(proof.path("after").path("fingerprint").asText())
                || !json.valueToTree(actual.rows()).equals(proof.path("after").path("rows")))
            throw new IllegalStateException("Current static target differs from prior verified readback");
        return receipt.toString();
    }
    public FrozenRequest plan(List<String> codes,boolean discover,LocalDate logicalDate) {
        var params=new LinkedHashMap<String,Object>();params.put("discover",discover);
        if(codes!=null && !codes.isEmpty()) params.put("codes",List.copyOf(codes));
        var frozen=definition().freeze(null,params,logicalDate,logicalDate,logicalDate);validate(frozen);return frozen;
    }
    private static void validate(FrozenRequest request) {
        if(!request.definition().equals(definition()) || !request.from().equals(request.logicalDate())
                || !request.to().equals(request.logicalDate())) throw new IllegalArgumentException("Static job observation-day contract required");
        boolean discover=(Boolean)request.parameters().get("discover");var codes=codes(request);
        if(discover==!codes.isEmpty()) throw new IllegalArgumentException("Choose discovery or explicit codes, not both or neither");
        if(new HashSet<>(codes).size()!=codes.size() || codes.stream().anyMatch(c->!StockDetailInfo.validCode(c)))
            throw new IllegalArgumentException("Unique valid source codes required");
    }
    @SuppressWarnings("unchecked") private static List<String> codes(FrozenRequest request) {
        return (List<String>)request.parameters().getOrDefault("codes",List.of());
    }
    public Result run(FrozenRequest request) throws Exception {
        return execute("stock-detail-"+UUID.randomUUID(),null,request);
    }
    /** Replay a bounded observation only when its predecessor ended before any publication submission. */
    public Result resume(FrozenRequest request,String priorRunId) throws Exception {
        validate(request);
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var prior=ledger.getRun(priorRunId);
        var entry=ledger.get(priorRunId);
        if(!Set.of(SyncRunState.FAILED,SyncRunState.CANCELLED).contains(entry.state())
                || !prior.jobId().equals(definition().jobId()) || prior.jobVersion()!=definition().version()
                || !prior.frozenJson().equals(SyncRequestIdentity.snapshotJson(request))
                || !prior.targetId().equals(targetId())
                || new DatasetIntervalLock(ledgerPath).findOwned(priorRunId,
                        DatasetIntervalLock.Scope.allDates(datasetId()))!=null
                || JobDefinitionJson.mapper().readTree(entry.payloadJson()).hasNonNull("publicationId"))
            throw new IllegalStateException("Prior static run cannot be safely replayed; reconcile uncertain writes first");
        String next="stock-detail-"+UUID.randomUUID();
        Path evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(next);
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("resume-intent.json"),JobDefinitionJson.mapper().writeValueAsString(
                Map.of("priorRunId",priorRunId,"priorState",entry.state(),"request",prior.frozenJson(),
                        "targetId",prior.targetId(),"replayScope","entire bounded observation")),
                StandardOpenOption.CREATE_NEW);
        return execute(next,null,request);
    }
    public Result execute(String runId,String parentId,FrozenRequest request) throws Exception {
        return executeInternal(runId,parentId,request,null);
    }
    private record PreparedInput(List<StockDetailInfo> rows,String receipt) {
        private PreparedInput { rows=List.copyOf(rows);Objects.requireNonNull(receipt); }
    }
    public Result executePrepared(String runId,String parentId,FrozenRequest request,
                                  List<StockDetailInfo> rows,String receipt) throws Exception {
        var input=new PreparedInput(rows,receipt);
        if(!request.definition().jobId().equals("write.stock_detail_info")
                || request.definition().version()!=1 || !request.definition().datasetId().equals(datasetId())
                || request.definition().datasetVersion()!=StockDetailInfoDataset.DEFINITION.schemaVersion()
                || request.mode()!=Mode.INGEST || request.from()!=null || request.to()!=null)
            throw new IllegalArgumentException("Prepared static write job contract required");
        var mapper=new com.zoutrankil.questdbwithdata.mapper.StockDetailInfoMapper();
        var batch=DatasetWritePreparation.prepareStatic(StockDetailInfoDataset.DEFINITION,input.rows(),
                mapper::values,new DatasetWritePreparation.Limits(10000,16*1024*1024));
        if(!batch.fingerprint().equals(request.parameters().get("payloadFingerprint")))
            throw new IllegalArgumentException("Prepared rows differ from frozen fingerprint");
        var proof=JobDefinitionJson.mapper().readTree(Path.of(receipt).toFile());
        if(!batch.fingerprint().equals(proof.path("fingerprint").asText())
                || !JobDefinitionJson.mapper().valueToTree(batch.rows()).equals(proof.path("rows")))
            throw new IllegalArgumentException("Prepared input differs from source receipt");
        return executeInternal(runId,parentId,request,input);
    }
    private Result executeInternal(String runId,String parentId,FrozenRequest request,PreparedInput input)
            throws Exception {
        if(input==null) validate(request);
        var storage=new StockDetailInfoStorage(jdbc,table);var identity=storage.preflight();
        var ledger=new SyncRunLedger(ledgerPath);var locks=new DatasetIntervalLock(ledgerPath);
        // Initial physical target is frozen; the publication receipt records its successor identity explicitly.
        String target=StaticTargetIdentity.identify(jdbc,table,identity);
        ledger.createRun(runId,parentId,target,request);
        Path evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(runId);String attempt=runId+"-attempt";
        String snapshotSlice=runId+"-snapshot";
        var lease=locks.acquire(runId,DatasetIntervalLock.Scope.allDates(datasetId()));
        if(lease==null) { move(ledger,runId,SyncRunState.FAILED,Map.of("errorCode","DATASET_INTERVAL_BUSY"));
            return new Result(runId,SyncRunState.FAILED,0,0,0,0,0,null,evidence.toString(),"DATASET_INTERVAL_BUSY"); }
        long started=System.nanoTime();
        var stopRequested=new java.util.concurrent.atomic.AtomicBoolean();
        BooleanSupplier cancelled=()-> {
            if(Thread.currentThread().isInterrupted() || System.nanoTime()-started>request.definition().timeout().toNanos()) {
                stopRequested.set(true);return true;
            }
            try { boolean stop=ledger.cancellationRequested(runId) || parentId!=null && ledger.cancellationRequested(parentId);
                if(stop) stopRequested.set(true);return stop; }
            catch(java.sql.SQLException failure) { throw new IllegalStateException("Cannot read cancellation",failure); }
        };
        var sourceRows=new ArrayList<StockDetailInfo>();var receipts=new ArrayList<String>();
        boolean submitted=false,attemptCreated=false,sliceCreated=false;
        String publicationId=null;StockDetailInfoMerge.Result merged=null;
        try {
            move(ledger,runId,SyncRunState.RUNNING,Map.of());
            ledger.createChild(attempt,SyncRunLedger.Kind.ATTEMPT,runId,runId);attemptCreated=true;
            move(ledger,attempt,SyncRunState.RUNNING,Map.of());
            ledger.createChild(snapshotSlice,SyncRunLedger.Kind.SLICE,runId,attempt);sliceCreated=true;
            move(ledger,snapshotSlice,SyncRunState.RUNNING,Map.of("sliceKind","static merged publication",
                    "targetBefore",target,"request",SyncRequestIdentity.snapshotJson(request)));
            checkCancelled(cancelled);var before=storage.snapshot();
            if(!before.identity().equals(identity)) throw new IllegalStateException("Target identity changed before source fetch");
            var observed=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if(input!=null) {
                sourceRows.addAll(input.rows());receipts.add(input.receipt());
            } else if((Boolean)request.parameters().get("discover")) {
                var found=new StockDetailInfoDiscovery(pages,evidence).fetch(observed,cancelled);
                sourceRows.addAll(found.rows());found.receipts().forEach(p->receipts.add(p.toString()));
            } else {
                var source=new StockDetailInfoSource(pages,evidence);
                for(String code:codes(request)) { checkCancelled(cancelled);var page=source.fetch(code,observed,cancelled);
                    sourceRows.addAll(page.rows());receipts.add(page.responseEvidence()); }
            }
            checkCancelled(cancelled);var prepared=StockDetailInfoStaging.prepare(before,sourceRows);merged=prepared.merge();
            if(merged.requiresPublication()) {
                StockDetailRecoveryEvidence.prepare(evidence,runId,target,request,observed,sourceRows,receipts,prepared);
                submitted=true;
                var staged=new StockDetailInfoStaging(jdbc).write(prepared,evidence,cancelled);
                var publication=new StockDetailInfoPublication(jdbc,ledgerPath).publish(lease,table,prepared,staged,cancelled);
                publicationId=publication.entry().intent().id();
            }
            var after=storage.snapshot();
            if(!after.rows().equals(prepared.rows())) throw new IllegalStateException("Final static target differs from complete merged rows");
            var finalByKey=new HashMap<String,StockDetailInfo>();
            for(var row:after.businessRows()) finalByKey.put(row.key(),row);
            for(var row:sourceRows) {
                var actual=finalByKey.get(row.key());
                if(actual==null || !StockDetailInfoMerge.sameBusinessValues(row,actual))
                    throw new IllegalStateException("Final static source-key values differ from source");
            }
            var proof=new LinkedHashMap<String,Object>();proof.put("runId",runId);proof.put("observedAt",observed);
            proof.put("snapshotSlice",snapshotSlice);
            proof.put("request",SyncRequestIdentity.snapshotJson(request));proof.put("sources",receipts);
            proof.put("beforeIdentity",before.identity());proof.put("beforeFingerprint",before.fingerprint());
            proof.put("after",after);proof.put("sourceRows",sourceRows);proof.put("merge",merged);
            proof.put("stagingSubmittedRows",merged.requiresPublication()?prepared.rows().size():0);
            proof.put("publicationId",publicationId);proof.put("checkpointKind","verified identity/content snapshot; no upstream date watermark");
            Files.createDirectories(evidence);Path receipt=evidence.resolve("completion.json");
            Files.writeString(receipt,JobDefinitionJson.mapper().writeValueAsString(proof),StandardOpenOption.CREATE_NEW);
            var state=sourceRows.isEmpty()?SyncRunState.VERIFIED_EMPTY:SyncRunState.VERIFIED;
            var payload=new LinkedHashMap<String,Object>();
            payload.put("evidence",receipt.toString());payload.put("sourceRows",sourceRows.size());
            payload.put("verifiedRows",sourceRows.size());payload.put("targetAfter",after.identity());
            payload.put("checkpoint",after.fingerprint());
            if(state==SyncRunState.VERIFIED) {
                var fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(JobDefinitionJson.mapper().writeValueAsBytes(sourceRows)));
                payload.put("verification",Map.of("passed",true,"expectedRows",sourceRows.size(),
                        "actualRows",sourceRows.size(),"matchedRows",sourceRows.size(),
                        "mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,
                        "readbackEvidence",receipt.toString(),"sourceFingerprint",fingerprint,
                        "writerStopped",true));
            } else {
                payload.put("sourceComplete",true);payload.put("returnedRows",0);
                payload.put("submittedRows",0);payload.put("responseEvidence",String.join(";",receipts));
            }
            move(ledger,snapshotSlice,state,payload);move(ledger,attempt,state,payload);move(ledger,runId,state,payload);
            String releaseError=null;
            try { locks.releaseVerified(lease); }
            catch(RuntimeException failure) { releaseError="VERIFIED_LOCK_RELEASE_PENDING"; }
            return new Result(runId,state,sourceRows.size(),merged.insertedRows(),merged.updatedRows(),merged.unchangedRows(),
                    sourceRows.size(),publicationId,receipt.toString(),releaseError);
        } catch(Exception failure) {
            if(failure instanceof StockDetailInfoPublication.Uncertain uncertain) publicationId=uncertain.publicationId();
            var state=submitted?SyncRunState.IN_DOUBT:stopRequested.get() || failure instanceof java.util.concurrent.CancellationException
                    ?SyncRunState.CANCELLED:SyncRunState.FAILED;
            var payload=new LinkedHashMap<String,Object>();payload.put("errorCode",failure.getClass().getSimpleName());
            payload.put("sourceRows",sourceRows.size());payload.put("publicationId",publicationId);payload.put("evidence",evidence.toString());
            var affected=new ArrayList<String>();
            if(sliceCreated) affected.add(snapshotSlice);if(attemptCreated) affected.add(attempt);affected.add(runId);
            for(String entry:affected) if(!ledger.get(entry).state().terminal()) move(ledger,entry,state,payload);
            if(submitted) {
                var journal=new StockDetailPublicationJournal(ledgerPath);
                if(!journal.requireLease(lease,true)) locks.retainInDoubt(lease);
            } else locks.releaseVerified(lease);
            return new Result(runId,state,sourceRows.size(),0,0,0,0,publicationId,evidence.toString(),failure.getClass().getSimpleName());
        }
    }
    private static void move(SyncRunLedger ledger,String id,SyncRunState state,Map<String,?> payload) throws Exception {
        ledger.transition(id,ledger.get(id).revision(),state,JobDefinitionJson.mapper().writeValueAsString(payload));
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Static sync cancelled or timed out");
    }
}
