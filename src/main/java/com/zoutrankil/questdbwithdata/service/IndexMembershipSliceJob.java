package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One industry per durable child run; the caller must serialize industry children. */
public final class IndexMembershipSliceJob {
    public record Result(String runId,SyncRunState state,int sourceRows,int inserted,int revised,int unchanged,
                         int submittedStageRows,String evidence,String errorCode) {}
    private final JdbcTemplate jdbc;private final TusharePageService pages;private final Path path;private final String table;
    @FunctionalInterface interface Hook {
        void afterPublication(String run) throws Exception;
        default void afterStage(String run) throws Exception {}
        default void afterPrepared(String run) throws Exception {}
        default IndexMembershipStaging staging(JdbcTemplate jdbc) { return new IndexMembershipStaging(jdbc); }
    }
    private final Hook hook;
    public IndexMembershipSliceJob(JdbcTemplate jdbc,TusharePageService pages,Path ledgerPath,String table) {
        this(jdbc,pages,ledgerPath,table,run->{});
    }
    IndexMembershipSliceJob(JdbcTemplate jdbc,TusharePageService pages,Path ledgerPath,String table,Hook hook) {
        this.jdbc=Objects.requireNonNull(jdbc);this.pages=Objects.requireNonNull(pages);
        path=ledgerPath.toAbsolutePath().normalize();DatasetDefinition.identifier(table);this.table=table;this.hook=Objects.requireNonNull(hook);
    }
    public String targetId() {
        var identity=new IndexMembershipStorage(jdbc,table).preflight();
        return StaticTargetIdentity.identify(jdbc,table,identity.id(),identity.directory());
    }
    public Result run(SyncJobDefinition.FrozenRequest request) throws Exception {
        return execute("membership-"+UUID.randomUUID(),null,request);
    }
    public Result finishInterrupted(String run,boolean writerStopped) throws Exception {
        return IndexMembershipRunRecovery.finish(jdbc,path,table,run,writerStopped);
    }
    public Result resume(SyncJobDefinition.FrozenRequest request,String priorRun) throws Exception {
        if(IndexMembershipJobPlan.scopes(request).size()!=1) throw new IllegalArgumentException("Single industry retry required");
        var ledger=SyncRunLedger.openReadOnly(path);var prior=ledger.getRun(priorRun);String target=targetId();
        if(!Set.of(SyncRunState.FAILED,SyncRunState.CANCELLED).contains(ledger.get(priorRun).state())
                || !prior.jobId().equals(IndexMembershipJobPlan.definition().jobId()) || prior.jobVersion()!=1
                || !prior.targetId().equals(target)
                || !SyncRequestIdentity.fingerprint(prior.frozenJson(),target).equals(SyncRequestIdentity.fingerprint(request,target))
                || new DatasetIntervalLock(path).findOwned(priorRun,DatasetIntervalLock.Scope.allDates("index_member"))!=null)
            throw new IllegalStateException("Reconcile uncertain membership runs before retry; request and target must remain frozen");
        String next="membership-"+UUID.randomUUID();Path folder=path.getParent().resolve("sync-evidence").resolve(next);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("resume-intent.json"),JobDefinitionJson.mapper().writeValueAsString(
                Map.of("priorRunId",priorRun,"request",prior.frozenJson(),"targetId",target)),StandardOpenOption.CREATE_NEW);
        return execute(next,null,request);
    }
    public Result execute(String run,String parent,SyncJobDefinition.FrozenRequest request) throws Exception {
        return execute(run,parent,request,()->false);
    }
    Result execute(String run,String parent,SyncJobDefinition.FrozenRequest request,BooleanSupplier parentCancelled) throws Exception {
        var scopes=IndexMembershipJobPlan.scopes(request);
        if(scopes.size()!=1) throw new IllegalArgumentException("One industry per child run; use serial coordination for batches");
        var scope=scopes.getFirst();
        if(table.equals("index_member") && scope.selection()!=IndexMembershipSource.Selection.CURRENT)
            throw new IllegalArgumentException("Historical members require an isolated target until consumer migration is accepted");
        String target=targetId();var ledger=new SyncRunLedger(path);var locks=new DatasetIntervalLock(path);
        ledger.createRun(run,parent,target,request);
        Path folder=path.getParent().resolve("sync-evidence").resolve(run);
        var lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("index_member"));
        if(lease==null) {
            move(ledger,run,SyncRunState.FAILED,Map.of("errorCode","DATASET_INTERVAL_BUSY"));
            return new Result(run,SyncRunState.FAILED,0,0,0,0,0,folder.toString(),"DATASET_INTERVAL_BUSY");
        }
        var entries=new ArrayList<String>();entries.add(run);long started=System.nanoTime();
        BooleanSupplier cancelled=()-> {
            if(parentCancelled.getAsBoolean() || Thread.currentThread().isInterrupted() || System.nanoTime()-started>request.definition().timeout().toNanos()) return true;
            try { return ledger.cancellationRequested(run) || parent!=null && ledger.cancellationRequested(parent); }
            catch(java.sql.SQLException e) { throw new IllegalStateException("Cannot read membership cancellation",e); }
        };
        boolean submitted=false;int sourceRows=0,submittedRows=0;
        try {
            move(ledger,run,SyncRunState.RUNNING,Map.of());String attempt=run+"-attempt",slice=run+"-"+scope.l2Code();
            ledger.createChild(attempt,SyncRunLedger.Kind.ATTEMPT,run,run);entries.addFirst(attempt);
            move(ledger,attempt,SyncRunState.RUNNING,Map.of());
            ledger.createChild(slice,SyncRunLedger.Kind.SLICE,run,attempt);entries.addFirst(slice);
            move(ledger,slice,SyncRunState.RUNNING,Map.of("scope",scope));
            check(cancelled);Files.createDirectories(folder);
            // Revalidate after acquiring the lease and before issuing the first member request.
            if(!IndexMembershipJobPlan.scopes(request).equals(scopes)) throw new IllegalStateException("Classification scope changed");
            var source=new IndexMembershipSource(pages,folder).fetch(scope,Instant.now().truncatedTo(ChronoUnit.MICROS),cancelled);
            IndexMembershipSourceEvidence.verify(source,scope,folder);
            sourceRows=source.rows().size();check(cancelled);
            var storage=new IndexMembershipStorage(jdbc,table);var before=storage.snapshot();
            if(!target.equals(StaticTargetIdentity.identify(jdbc,table,before.identity().id(),before.identity().directory())))
                throw new IllegalStateException("Membership target changed after run creation");
            var prepared=IndexMembershipStaging.prepare(before,source.rows(),scope.l2Code());var merge=prepared.merge();
            Files.writeString(folder.resolve("prepared.json"),JobDefinitionJson.mapper().writeValueAsString(Map.of(
                    "runId",run,"targetId",target,"request",SyncRequestIdentity.snapshotJson(request),"scope",scope,
                    "source",source,"prepared",prepared)),StandardOpenOption.CREATE_NEW);
            hook.afterPrepared(run);
            check(cancelled);String publication=null;
            if(merge.requiresWrite()) {
                submitted=true;submittedRows=prepared.rows().size();
                var stage=hook.staging(jdbc).write(prepared,folder,cancelled);
                hook.afterStage(run);
                publication=new IndexMembershipPublication(jdbc,path).publish(lease,table,prepared,stage,cancelled).publication().intent().id();
                hook.afterPublication(run);
            }
            var actual=storage.snapshot();
            if(!actual.rows().equals(prepared.rows())) throw new IllegalStateException("Membership full-key/full-value readback mismatch");
            var proof=new LinkedHashMap<String,Object>();
            proof.put("runId",run);proof.put("scope",scope);proof.put("source",source);proof.put("before",before);proof.put("actual",actual);
            proof.put("merge",merge);proof.put("publicationId",publication);proof.put("submittedStageRows",submittedRows);
            proof.put("sourceComplete",true);proof.put("checkpointBefore",before.fingerprint());proof.put("checkpointAfter",actual.fingerprint());
            Path receipt=folder.resolve("completion.json");
            Files.writeString(receipt,JobDefinitionJson.mapper().writeValueAsString(proof),StandardOpenOption.CREATE_NEW);
            SyncRunState state=sourceRows==0?SyncRunState.VERIFIED_EMPTY:SyncRunState.VERIFIED;
            Map<String,?> payload;
            if(sourceRows==0) payload=Map.of("sourceComplete",true,"returnedRows",0,"submittedRows",0,
                    "responseEvidence",source.responseEvidence(),"readbackEvidence",receipt.toString(),"checkpoint",actual.fingerprint());
            else {
                var verification=Map.of("passed",true,"expectedRows",sourceRows,"actualRows",sourceRows,"matchedRows",sourceRows,
                        "mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,"readbackEvidence",receipt.toString(),
                        "sourceFingerprint",source.sourceFingerprint(),"writerStopped",true);
                payload=Map.of("verification",verification,"checkpoint",actual.fingerprint(),"evidence",receipt.toString());
            }
            for(String entry:entries) move(ledger,entry,state,payload);
            String releaseError=null;
            try { locks.releaseVerified(lease); } catch(RuntimeException e) { releaseError="VERIFIED_LOCK_RELEASE_PENDING"; }
            return new Result(run,state,sourceRows,merge.inserted(),merge.revised(),merge.unchanged(),submittedRows,receipt.toString(),releaseError);
        } catch(Exception failure) {
            var state=submitted?SyncRunState.IN_DOUBT:cancelled.getAsBoolean() || failure instanceof java.util.concurrent.CancellationException
                    ?SyncRunState.CANCELLED:SyncRunState.FAILED;
            var payload=new LinkedHashMap<String,Object>();
            payload.put("errorCode",failure.getClass().getSimpleName());payload.put("sourceRows",sourceRows);
            payload.put("evidence",folder.toString());
            var root=failure.getCause()==null?failure:failure.getCause();
            payload.put("causeCode",root.getClass().getSimpleName());
            if(root.getStackTrace().length>0) {
                var site=root.getStackTrace()[0];
                payload.put("causeSite",site.getClassName()+"."+site.getMethodName()+":"+site.getLineNumber());
            }
            for(String entry:entries) if(!ledger.get(entry).state().terminal()) move(ledger,entry,state,payload);
            var actual=locks.findOwned(run,lease.scope());
            if(submitted) { if(actual!=null && !actual.inDoubt()) locks.retainInDoubt(actual); }
            else if(actual!=null) locks.releaseVerified(actual);
            return new Result(run,state,sourceRows,0,0,0,submittedRows,folder.toString(),failure.getClass().getSimpleName());
        }
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Membership job cancelled or timed out");
    }
    private static void move(SyncRunLedger ledger,String id,SyncRunState state,Map<String,?> payload) throws Exception {
        ledger.transition(id,ledger.get(id).revision(),state,JobDefinitionJson.mapper().writeValueAsString(payload));
    }
}
