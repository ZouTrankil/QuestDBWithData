package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.IndexMembershipMapper;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Local prepared rows use their own receipt and job identity, never a synthetic Tushare response. */
public final class IndexMembershipPreparedJob {
    public record Result(String runId,SyncRunState state,int supplied,int verified,String evidence,String errorCode) {}
    private final JdbcTemplate jdbc;
    private final Path path;
    private final String table;
    @FunctionalInterface interface Hook {
        void afterStage(String run) throws Exception;
        default void afterPublication(String run) throws Exception {}
    }
    private final Hook hook;
    IndexMembershipPreparedJob(JdbcTemplate jdbc,Path path,String table) {
        this(jdbc,path,table,run->{});
    }
    IndexMembershipPreparedJob(JdbcTemplate jdbc,Path path,String table,Hook hook) {
        this.jdbc=Objects.requireNonNull(jdbc);this.path=path.toAbsolutePath().normalize();
        DatasetDefinition.identifier(table);this.table=table;this.hook=Objects.requireNonNull(hook);
    }
    Result execute(String run,String parent,SyncJobDefinition.FrozenRequest request,List<IndexMembership> rows,Path receipt)
            throws Exception {
        if(table.equals("index_member")) throw new IllegalStateException("Formal membership consumers have not accepted prepared publication");
        validate(request,rows,receipt);
        var storage=new IndexMembershipStorage(jdbc,table);var before=storage.snapshot();
        String target=StaticTargetIdentity.identify(jdbc,table,before.identity().id(),before.identity().directory());
        requireReceipt(request,rows,receipt,target);
        var ledger=new SyncRunLedger(path);var locks=new DatasetIntervalLock(path);
        ledger.createRun(run,parent,target,request);
        Path folder=path.getParent().resolve("sync-evidence").resolve(run);
        var lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("index_member"));
        if(lease==null) {
            move(ledger,run,SyncRunState.FAILED,Map.of("errorCode","DATASET_INTERVAL_BUSY"));
            return new Result(run,SyncRunState.FAILED,rows.size(),0,folder.toString(),"DATASET_INTERVAL_BUSY");
        }
        var entries=new ArrayList<String>();entries.add(run);
        boolean submitted=false;int submittedRows=0;
        long started=System.nanoTime();
        BooleanSupplier cancelled=()-> {
            if(Thread.currentThread().isInterrupted() || System.nanoTime()-started>request.definition().timeout().toNanos()) return true;
            try { return ledger.cancellationRequested(run) || parent!=null && ledger.cancellationRequested(parent); }
            catch(java.sql.SQLException failure) { throw new IllegalStateException("Cannot read prepared membership cancellation",failure); }
        };
        try {
            move(ledger,run,SyncRunState.RUNNING,Map.of());
            String attempt=run+"-attempt",slice=run+"-prepared";
            ledger.createChild(attempt,SyncRunLedger.Kind.ATTEMPT,run,run);entries.addFirst(attempt);
            move(ledger,attempt,SyncRunState.RUNNING,Map.of());
            ledger.createChild(slice,SyncRunLedger.Kind.SLICE,run,attempt);entries.addFirst(slice);
            move(ledger,slice,SyncRunState.RUNNING,Map.of("sourceKind","prepared-write-request"));
            check(cancelled);Files.createDirectories(folder);
            requireReceipt(request,rows,receipt,target);
            before=storage.snapshot();
            if(!target.equals(StaticTargetIdentity.identify(jdbc,table,before.identity().id(),before.identity().directory())))
                throw new IllegalStateException("Prepared membership target changed after admission");
            var prepared=IndexMembershipStaging.preparePrepared(before,rows,rows.getFirst().indexCode());
            byte[] sourceBytes=IndexMembershipSourceEvidence.bounded(receipt,IndexMembershipStorage.MAX_BYTES);
            String sourceHash=IndexMembershipSourceEvidence.hash(sourceBytes);
            Files.writeString(folder.resolve("prepared.json"),JobDefinitionJson.mapper().writeValueAsString(Map.of(
                    "runId",run,"targetId",target,"request",SyncRequestIdentity.snapshotJson(request),
                    "sourceKind","prepared-write-request","sourceReceipt",receipt.toString(),"sourceHash",sourceHash,
                    "prepared",prepared)),StandardOpenOption.CREATE_NEW);
            check(cancelled);String publication=null;
            if(prepared.merge().requiresWrite()) {
                submitted=true;submittedRows=prepared.rows().size();
                var stage=new IndexMembershipStaging(jdbc).write(prepared,folder,cancelled);
                hook.afterStage(run);
                publication=new IndexMembershipPublication(jdbc,path).publish(lease,table,prepared,stage,cancelled)
                        .publication().intent().id();
                hook.afterPublication(run);
            }
            var actual=storage.snapshot();
            if(!actual.rows().equals(prepared.rows())) throw new IllegalStateException("Prepared membership full-value readback differs");
            var indexed=new HashMap<IndexMembership.Key,IndexMembership>();
            actual.businessRows().forEach(row->indexed.put(row.key(),row));
            for(var row:rows) if(!row.equals(indexed.get(row.key())))
                throw new IllegalStateException("Prepared membership supplied-key readback differs");
            var proof=new LinkedHashMap<String,Object>();
            proof.put("runId",run);proof.put("sourceKind","prepared-write-request");proof.put("sourceReceipt",receipt.toString());
            proof.put("sourceHash",sourceHash);proof.put("before",before);proof.put("actual",actual);
            proof.put("prepared",prepared);proof.put("publicationId",publication);proof.put("submittedStageRows",submittedRows);
            Path completed=folder.resolve("completion.json");
            Files.writeString(completed,JobDefinitionJson.mapper().writeValueAsString(proof),StandardOpenOption.CREATE_NEW);
            var verification=Map.of("passed",true,"expectedRows",rows.size(),"actualRows",rows.size(),
                    "matchedRows",rows.size(),"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,
                    "readbackEvidence",completed.toString(),"sourceFingerprint",sourceHash,"writerStopped",true);
            var payload=Map.of("verification",verification,"checkpoint",actual.fingerprint(),"evidence",completed.toString());
            for(String id:entries) move(ledger,id,SyncRunState.VERIFIED,payload);
            String releaseError=null;
            try { locks.releaseVerified(lease); }
            catch(RuntimeException failure) { releaseError="VERIFIED_LOCK_RELEASE_PENDING"; }
            return new Result(run,SyncRunState.VERIFIED,rows.size(),rows.size(),completed.toString(),releaseError);
        } catch(Exception failure) {
            var state=submitted?SyncRunState.IN_DOUBT:(failure instanceof java.util.concurrent.CancellationException || cancelled.getAsBoolean())
                    ?SyncRunState.CANCELLED:SyncRunState.FAILED;
            var payload=Map.of("errorCode",failure.getClass().getSimpleName(),"sourceRows",rows.size(),"evidence",folder.toString());
            for(String id:entries) if(!ledger.get(id).state().terminal()) move(ledger,id,state,payload);
            var actual=locks.findOwned(run,lease.scope());
            if(submitted) { if(actual!=null && !actual.inDoubt()) locks.retainInDoubt(actual); }
            else if(actual!=null) locks.releaseVerified(actual);
            return new Result(run,state,rows.size(),0,folder.toString(),failure.getClass().getSimpleName());
        }
    }
    String revalidate(String run,String expectedTarget,SyncJobDefinition.FrozenRequest request) throws Exception {
        var ledger=SyncRunLedger.openReadOnly(path);var saved=ledger.getRun(run);
        if(ledger.get(run).state()!=SyncRunState.VERIFIED || !saved.targetId().equals(expectedTarget)
                || !saved.frozenJson().equals(SyncRequestIdentity.snapshotJson(request)))
            throw new IllegalStateException("Completed prepared membership child differs from frozen request");
        Path folder=path.getParent().resolve("sync-evidence").resolve(run);
        var json=JobDefinitionJson.mapper();
        var frozen=json.readTree(IndexMembershipSourceEvidence.bounded(folder.resolve("prepared.json"),96*1024*1024));
        if(!frozen.path("runId").asText().equals(run) || !frozen.path("targetId").asText().equals(expectedTarget)
                || !frozen.path("request").asText().equals(saved.frozenJson())
                || !frozen.path("sourceKind").asText().equals("prepared-write-request"))
            throw new IllegalStateException("Prepared membership evidence identity changed");
        var prepared=json.treeToValue(frozen.path("prepared"),IndexMembershipStaging.Prepared.class);
        if(!prepared.l2Code().startsWith("prepared:") || !prepared.equals(IndexMembershipStaging.preparePrepared(
                prepared.before(),prepared.source(),prepared.l2Code().substring(9))))
            throw new IllegalStateException("Prepared membership merge changed");
        Path source=Path.of(frozen.path("sourceReceipt").asText());
        requireReceipt(request,prepared.source(),source,expectedTarget);
        if(!IndexMembershipSourceEvidence.hash(IndexMembershipSourceEvidence.bounded(source,IndexMembershipStorage.MAX_BYTES))
                .equals(frozen.path("sourceHash").asText()))
            throw new IllegalStateException("Completed prepared membership input bytes changed");
        var proof=json.readTree(IndexMembershipSourceEvidence.bounded(folder.resolve("completion.json"),96*1024*1024));
        var actual=new IndexMembershipStorage(jdbc,table).snapshot();
        if(!proof.path("runId").asText().equals(run) || !proof.path("sourceHash").asText().equals(frozen.path("sourceHash").asText())
                || !actual.equals(json.treeToValue(proof.path("actual"),IndexMembershipStorage.Snapshot.class))
                || !prepared.rows().equals(actual.rows())
                || new DatasetIntervalLock(path).findOwned(run,DatasetIntervalLock.Scope.allDates("index_member"))!=null)
            throw new IllegalStateException("Completed prepared membership target or lease changed");
        return folder.resolve("completion.json").toString();
    }
    static SyncJobDefinition.FrozenRequest restore(String frozenJson) throws Exception {
        var json=JobDefinitionJson.mapper();var saved=json.readTree(frozenJson);
        var definition=json.treeToValue(saved.path("definition"),SyncJobDefinition.class);
        if(!definition.jobId().equals("write.index_member") || definition.version()!=1
                || !definition.datasetId().equals("index_member")
                || definition.datasetVersion()!=IndexMembershipDataset.DEFINITION.schemaVersion())
            throw new IllegalArgumentException("Frozen prepared membership job changed");
        Map<String,Object> parameters=json.convertValue(saved.path("parameters"),new com.fasterxml.jackson.core.type.TypeReference<>() {});
        var request=definition.freeze(SyncJobDefinition.Mode.INGEST,parameters,null,null,
                LocalDate.parse(saved.path("logicalDate").asText()));
        if(!json.readTree(frozenJson).equals(json.readTree(SyncRequestIdentity.snapshotJson(request))))
            throw new IllegalArgumentException("Prepared membership request cannot be reconstructed exactly");
        return request;
    }
    static void validate(SyncJobDefinition.FrozenRequest request,List<IndexMembership> rows,Path receipt) {
        if(!request.definition().jobId().equals("write.index_member") || request.mode()!=SyncJobDefinition.Mode.INGEST
                || request.definition().version()!=1 || !request.definition().owner().equals("index_membership_owner")
                || request.definition().datasetVersion()!=IndexMembershipDataset.DEFINITION.schemaVersion()
                || !request.definition().datasetId().equals("index_member")
                || !request.definition().ratePolicyRef().equals("prepared.local")
                || !request.definition().slicePolicyRef().equals("prepared.membership.l2")
                || !request.definition().verificationPolicyRef().equals("questdb.full_key_values")
                || request.from()!=null || request.to()!=null || rows.isEmpty() || rows.size()>=IndexMembershipMerge.MAX_SLICE_ROWS
                || request.definition().budget().maxRows()<rows.size()
                || !request.parameters().keySet().equals(Set.of("groupBatch","memberBatch","planFingerprint","payloadFingerprint"))
                || !(request.parameters().get("payloadFingerprint") instanceof String hash) || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Bounded prepared membership write required");
        Objects.requireNonNull(receipt);
        String scope=rows.getFirst().indexCode();
        if(rows.stream().anyMatch(row->!row.indexCode().equals(scope) || !row.level().equals("L2")))
            throw new IllegalArgumentException("One L2 industry per prepared membership batch");
    }
    static void requireReceipt(SyncJobDefinition.FrozenRequest request,List<IndexMembership> rows,
                                       Path receipt,String target) throws Exception {
        var json=JobDefinitionJson.mapper();var proof=json.readTree(IndexMembershipSourceEvidence.bounded(receipt,IndexMembershipStorage.MAX_BYTES));
        var mapper=new IndexMembershipMapper();
        var values=rows.stream().map(mapper::values).toList();
        if(!proof.path("sourceKind").asText().equals("prepared-write-request")
                || !proof.path("targetId").asText().equals(target)
                || !proof.path("fingerprint").asText().equals(request.parameters().get("payloadFingerprint"))
                || !proof.path("rows").equals(json.valueToTree(values)))
            throw new IllegalArgumentException("Prepared membership receipt differs from frozen rows");
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Prepared membership cancelled");
    }
    private static void move(SyncRunLedger ledger,String id,SyncRunState state,Map<String,?> payload) throws Exception {
        ledger.transition(id,ledger.get(id).revision(),state,JobDefinitionJson.mapper().writeValueAsString(payload));
    }
}
