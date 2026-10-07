package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Serial bounded industry children; an incomplete child stops the batch before the next industry. */
public final class IndexMembershipBatchJob {
    public record Member(int ordinal,String industry,String childRunId,SyncRunState state,boolean reused) {}
    public record Result(String runId,SyncRunState state,List<Member> members,String errorCode) {
        public Result { members=List.copyOf(members); }
    }
    private record Step(IndexMembershipStorage.Snapshot before,IndexMembershipStorage.Snapshot after) {}
    private final JdbcTemplate jdbc;private final Path path;private final String table;private final IndexMembershipSliceJob child;
    @FunctionalInterface interface Hook {
        void afterMember(String run,int ordinal) throws Exception;
        default void afterStoppedAttempt(String run) throws Exception {}
        default void afterPlan(String run) throws Exception {}
        default void afterSlots(String run) throws Exception {}
    }
    private final Hook hook;
    public IndexMembershipBatchJob(JdbcTemplate jdbc,TusharePageService pages,Path path,String table) {
        this(jdbc,pages,path,table,(run,ordinal)->{});
    }
    IndexMembershipBatchJob(JdbcTemplate jdbc,TusharePageService pages,Path path,String table,Hook hook) {
        this.jdbc=Objects.requireNonNull(jdbc);this.path=path.toAbsolutePath().normalize();DatasetDefinition.identifier(table);this.table=table;
        this.hook=Objects.requireNonNull(hook);
        child=new IndexMembershipSliceJob(jdbc,pages,this.path,table);
    }
    public Result run(SyncJobDefinition.FrozenRequest request) throws Exception { return execute(request,null); }
    public Result resume(SyncJobDefinition.FrozenRequest request,String prior) throws Exception { return execute(request,Objects.requireNonNull(prior)); }
    public Result resumeStopped(SyncJobDefinition.FrozenRequest request,String prior,boolean writerStopped) throws Exception {
        if(!writerStopped) throw new IllegalStateException("Stopped batch coordinator proof required");
        var scopes=IndexMembershipJobPlan.scopes(request);var ledger=new SyncRunLedger(path);var old=ledger.getRun(prior);
        if(!Set.of(SyncRunState.RUNNING,SyncRunState.PARTIAL).contains(ledger.get(prior).state()) || !old.jobId().equals("data.index_member") || old.jobVersion()!=1
                || !SyncRequestIdentity.fingerprint(old.frozenJson(),old.targetId()).equals(SyncRequestIdentity.fingerprint(request,old.targetId())))
            throw new IllegalStateException("Stopped running batch with identical frozen request required");
        var json=JobDefinitionJson.mapper();var plan=json.readTree(IndexMembershipSourceEvidence.bounded(folder(prior).resolve("batch-plan.json"),1024*1024));
        var identity=json.treeToValue(plan.path("initialIdentity"),IndexMembershipStorage.Identity.class);
        if(!plan.path("runId").asText().equals(prior) || !plan.path("table").asText().equals(table)
                || !old.targetId().equals(StaticTargetIdentity.identify(jdbc,table,identity.id(),identity.directory())))
            throw new IllegalStateException("Stopped batch endpoint differs");
        var locks=new DatasetIntervalLock(path);
        var entries=ledger.entries(prior,null,40);
        if(entries.size()==1 && entries.getFirst().kind()==SyncRunLedger.Kind.RUN) {
            var current=new IndexMembershipStorage(jdbc,table).snapshot();
            if(!current.identity().equals(identity) || !current.fingerprint().equals(plan.path("initialFingerprint").asText())
                    || new ReferencePublicationJournal(path,"index_member").findForRun(prior).isPresent()
                    || locks.findOwned(prior,DatasetIntervalLock.Scope.allDates("index_member"))!=null)
                throw new IllegalStateException("Unstarted membership batch target or publication changed");
            move(ledger,prior,SyncRunState.PARTIAL,Map.of("writerStopped",true,"errorCode","COORDINATOR_INTERRUPTED_BEFORE_ATTEMPT"));
            var earlyGuard=locks.findOwned(prior,DatasetIntervalLock.Scope.allDates("index_member_batch"));
            if(earlyGuard!=null) {
                if(earlyGuard.inDoubt()) locks.releaseAfterReconciliation(earlyGuard,true,true);
                else locks.releaseVerified(earlyGuard);
            }
            return execute(request,prior);
        }
        for(int i=0;i<scopes.size();i++) {
            var entry=ledger.get(slot(prior,i));
            if(entry.kind()!=SyncRunLedger.Kind.SLICE || !entry.runId().equals(prior) || !entry.parentId().equals(prior+"-attempt"))
                throw new IllegalStateException("Stopped batch slot ownership differs");
            String childId=json.readTree(entry.payloadJson()).path("childRunId").asText(null);
            if(childId!=null && (!ledger.get(childId).state().terminal()
                    || locks.findOwned(childId,DatasetIntervalLock.Scope.allDates("index_member"))!=null))
                throw new IllegalStateException("Reconcile stopped batch child before continuing");
        }
        var attempt=ledger.get(prior+"-attempt");
        if(attempt.kind()!=SyncRunLedger.Kind.ATTEMPT || !prior.equals(attempt.parentId())
                || !Set.of(SyncRunState.RUNNING,SyncRunState.PARTIAL).contains(attempt.state()))
            throw new IllegalStateException("Stopped batch attempt differs");
        if(locks.findOwned(prior,DatasetIntervalLock.Scope.allDates("index_member"))!=null)
            throw new IllegalStateException("Stopped final-verification lease needs reconciliation");
        var proof=Map.of("writerStopped",true,"errorCode","COORDINATOR_INTERRUPTED","continuedByNewRun",true);
        if(attempt.state()!=SyncRunState.PARTIAL) move(ledger,attempt.id(),SyncRunState.PARTIAL,proof);
        hook.afterStoppedAttempt(prior);
        if(ledger.get(prior).state()!=SyncRunState.PARTIAL) move(ledger,prior,SyncRunState.PARTIAL,proof);
        var guard=locks.findOwned(prior,DatasetIntervalLock.Scope.allDates("index_member_batch"));
        if(guard!=null) { if(guard.inDoubt()) locks.releaseAfterReconciliation(guard,true,true);else locks.releaseVerified(guard); }
        return execute(request,prior);
    }
    private Result execute(SyncJobDefinition.FrozenRequest request,String prior) throws Exception {
        return execute(request,prior,null,null);
    }
    Result runAsChild(String run,String parent,SyncJobDefinition.FrozenRequest request) throws Exception {
        return execute(request,null,Objects.requireNonNull(run),Objects.requireNonNull(parent));
    }
    private Result execute(SyncJobDefinition.FrozenRequest request,String prior,String reservedRun,String parent) throws Exception {
        var scopes=IndexMembershipJobPlan.scopes(request);var json=JobDefinitionJson.mapper();var ledger=new SyncRunLedger(path);
        var requests=new ArrayList<SyncJobDefinition.FrozenRequest>();
        for(var scope:scopes) {
            var parameters=new LinkedHashMap<String,Object>(request.parameters());parameters.put("industryCodes",List.of(scope.l2Code()));
            requests.add(IndexMembershipJobPlan.definition().freeze(request.mode(),parameters,request.from(),request.to(),request.logicalDate()));
        }
        var previous=new ArrayList<String>();
        if(prior!=null) {
            var old=ledger.getRun(prior);
            if(!ledger.get(prior).state().terminal() || !old.jobId().equals("data.index_member") || old.jobVersion()!=1
                    || !SyncRequestIdentity.fingerprint(old.frozenJson(),old.targetId()).equals(SyncRequestIdentity.fingerprint(request,old.targetId())))
                throw new IllegalStateException("Prior membership batch is active or its frozen request differs");
            var oldPlan=json.readTree(IndexMembershipSourceEvidence.bounded(folder(prior).resolve("batch-plan.json"),1024*1024));
            var identity=json.treeToValue(oldPlan.path("initialIdentity"),IndexMembershipStorage.Identity.class);
            String expectedFingerprint=oldPlan.path("initialFingerprint").asText();
            if(!oldPlan.path("runId").asText().equals(prior) || !oldPlan.path("table").asText().equals(table)
                    || !expectedFingerprint.matches("[0-9a-f]{64}")
                    || !old.targetId().equals(StaticTargetIdentity.identify(jdbc,table,identity.id(),identity.directory())))
                throw new IllegalStateException("Prior membership batch target differs");
            boolean incomplete=false;
            var priorEntries=ledger.entries(prior,null,40);
            boolean unstarted=priorEntries.size()==1 && priorEntries.getFirst().kind()==SyncRunLedger.Kind.RUN;
            for(int i=0;i<scopes.size() && !unstarted;i++) {
                var slot=ledger.get(slot(prior,i));String previousChild=json.readTree(slot.payloadJson()).path("childRunId").asText(null);
                if(previousChild!=null) {
                    var oldChild=ledger.get(previousChild);var oldChildRun=ledger.getRun(previousChild);
                    if(!oldChild.state().terminal() || new DatasetIntervalLock(path).findOwned(previousChild,DatasetIntervalLock.Scope.allDates("index_member"))!=null)
                        throw new IllegalStateException("Reconcile unfinished industry child before resuming batch");
                    if(Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(oldChild.state())) {
                        if(incomplete || slot.state()!=oldChild.state()) throw new IllegalStateException("Prior industry order or state differs");
                        IndexMembershipCompletedRun.verify(jdbc,path,table,previousChild,requests.get(i));
                        if(prior.equals(oldChildRun.parentRunId())) {
                            var step=step(previousChild);
                            if(!step.before().identity().equals(identity) || !step.before().fingerprint().equals(expectedFingerprint))
                                throw new IllegalStateException("Prior membership publication chain differs");
                            identity=step.after().identity();expectedFingerprint=step.after().fingerprint();
                        }
                    } else incomplete=true;
                } else incomplete=true;
                previous.add(previousChild);
            }
            if(unstarted) for(int i=0;i<scopes.size();i++) previous.add(null);
            if(Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(ledger.get(prior).state()) && incomplete)
                throw new IllegalStateException("Completed membership batch has an unverified slot");
            var current=new IndexMembershipStorage(jdbc,table).snapshot();
            if(!current.identity().equals(identity) || !current.fingerprint().equals(expectedFingerprint))
                throw new IllegalStateException("Prior membership batch full target changed before resume");
        }
        String run=reservedRun==null?"membership-batch-"+UUID.randomUUID():reservedRun;var initial=new IndexMembershipStorage(jdbc,table).snapshot();
        var identity=initial.identity();
        String target=StaticTargetIdentity.identify(jdbc,table,identity.id(),identity.directory());ledger.createRun(run,parent==null?prior:parent,target,request);
        var locks=new DatasetIntervalLock(path);var guard=locks.acquire(run,DatasetIntervalLock.Scope.allDates("index_member_batch"));
        if(guard==null) { move(ledger,run,SyncRunState.FAILED,Map.of("errorCode","MEMBERSHIP_BATCH_BUSY"));return new Result(run,SyncRunState.FAILED,List.of(),"MEMBERSHIP_BATCH_BUSY"); }
        var members=new ArrayList<Member>();String attempt=run+"-attempt";String activeSlot=null,activeChild=null;boolean attempted=false;
        long started=System.nanoTime();BooleanSupplier cancelled=()-> {
            if(Thread.currentThread().isInterrupted() || System.nanoTime()-started>request.definition().timeout().toNanos()) return true;
            try { return parent!=null && ledger.cancellationRequested(parent); }
            catch(java.sql.SQLException failure) { throw new IllegalStateException("Cannot read parent group cancellation",failure); }
        };
        try {
            Files.createDirectories(folder(run));FileEvidenceStore.writeNewUtf8(folder(run).resolve("batch-plan.json"),json.writeValueAsString(Map.of(
                    "runId",run,"table",table,"initialIdentity",identity,"initialFingerprint",initial.fingerprint(),
                    "request",SyncRequestIdentity.snapshotJson(request))));
            move(ledger,run,SyncRunState.RUNNING,Map.of());hook.afterPlan(run);
            ledger.createChild(attempt,SyncRunLedger.Kind.ATTEMPT,run,run);attempted=true;
            move(ledger,attempt,SyncRunState.RUNNING,Map.of());
            for(int i=0;i<scopes.size();i++) ledger.createChild(slot(run,i),SyncRunLedger.Kind.SLICE,run,attempt);
            hook.afterSlots(run);
            var expectedSnapshot=initial;
            for(int i=0;i<scopes.size();i++) {
                if(cancelled.getAsBoolean() || ledger.cancellationRequested(run)) throw new java.util.concurrent.CancellationException();
                activeSlot=slot(run,i);String old=prior==null?null:previous.get(i);
                boolean reuse=old!=null && Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(ledger.get(old).state());
                activeChild=reuse?old:run+"-child-"+i;
                move(ledger,activeSlot,SyncRunState.RUNNING,Map.of("childRunId",activeChild,"industry",scopes.get(i).l2Code()));
                if(!reuse) {
                    var result=child.execute(activeChild,run,requests.get(i),cancelled);
                    if(result.state()==SyncRunState.CANCELLED) throw new java.util.concurrent.CancellationException("Industry child cancelled");
                    if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(result.state()))
                        throw new IllegalStateException("Industry child incomplete: "+result.state());
                }
                var verified=IndexMembershipCompletedRun.verify(jdbc,path,table,activeChild,requests.get(i));
                if(!reuse) {
                    var step=step(activeChild);
                    if(!step.before().equals(expectedSnapshot)) throw new IllegalStateException("Membership child before snapshot breaks serial chain");
                    expectedSnapshot=step.after();
                }
                move(ledger,activeSlot,verified.state(),payload(verified));
                members.add(new Member(i,scopes.get(i).l2Code(),activeChild,verified.state(),reuse));activeSlot=null;activeChild=null;
                hook.afterMember(run,i);
            }
            var verificationLease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("index_member"));
            if(verificationLease==null) throw new IllegalStateException("Membership final readback lease busy");
            try {
                if(!new IndexMembershipStorage(jdbc,table).snapshot().equals(expectedSnapshot))
                    throw new IllegalStateException("Membership final target breaks serial chain");
                var receipts=new ArrayList<IndexMembershipCompletedRun.Proof>();
                for(int i=0;i<members.size();i++) {
                    if(cancelled.getAsBoolean() || ledger.cancellationRequested(run)) throw new java.util.concurrent.CancellationException();
                    receipts.add(IndexMembershipCompletedRun.verify(jdbc,path,table,members.get(i).childRunId(),requests.get(i)));
                }
                Path receipt=folder(run).resolve("batch-completion.json");FileEvidenceStore.writeNewUtf8(receipt,json.writeValueAsString(Map.of(
                        "runId",run,"members",members,"readback",receipts)));
                boolean empty=members.stream().allMatch(m->m.state()==SyncRunState.VERIFIED_EMPTY);
                var state=empty?SyncRunState.VERIFIED_EMPTY:SyncRunState.VERIFIED;
                Map<String,?> proof=empty?Map.of("sourceComplete",true,"returnedRows",0,"submittedRows",0,"responseEvidence",receipt.toString())
                        :Map.of("metric","verifiedIndustryRuns","verification",Map.of("passed",true,"expectedRows",members.size(),
                        "actualRows",members.size(),"matchedRows",members.size(),"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,
                        "readbackEvidence",receipt.toString(),"sourceFingerprint",IndexMembershipSourceEvidence.hash(json.writeValueAsBytes(receipts))));
                move(ledger,attempt,state,proof);move(ledger,run,state,proof);return new Result(run,state,members,null);
            } finally { locks.releaseVerified(verificationLease); }
        } catch(Exception failure) {
            var state=failure instanceof java.util.concurrent.CancellationException?SyncRunState.CANCELLED:members.isEmpty()?SyncRunState.FAILED:SyncRunState.PARTIAL;
            var proof=new LinkedHashMap<String,Object>();proof.put("errorCode",failure.getClass().getSimpleName());
            if(failure.getStackTrace().length>0) {
                var site=failure.getStackTrace()[0];
                proof.put("errorSite",site.getClassName()+"."+site.getMethodName()+":"+site.getLineNumber());
                if(site.getClassName().equals(IndexMembershipCompletedRun.class.getName()))
                    proof.put("errorDetail",failure.getMessage());
            }
            if(activeChild!=null) proof.put("childRunId",activeChild);
            if(activeSlot!=null && !ledger.get(activeSlot).state().terminal())
                move(ledger,activeSlot,state==SyncRunState.CANCELLED?SyncRunState.CANCELLED:SyncRunState.FAILED,proof);
            if(attempted && !ledger.get(attempt).state().terminal()) move(ledger,attempt,state,proof);
            if(!ledger.get(run).state().terminal()) move(ledger,run,state,proof);
            return new Result(run,ledger.get(run).state(),members,failure.getClass().getSimpleName());
        } finally { locks.releaseVerified(guard); }
    }
    private Path folder(String run) { return path.getParent().resolve("sync-evidence").resolve(run); }
    String revalidateGroupChild(String run,String expectedTarget,SyncJobDefinition.FrozenRequest request) throws Exception {
        var scopes=IndexMembershipJobPlan.scopes(request);var ledger=SyncRunLedger.openReadOnly(path);var prior=ledger.getRun(run);
        if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(ledger.get(run).state())
                || !prior.targetId().equals(expectedTarget)
                || !SyncRequestIdentity.fingerprint(prior.frozenJson(),expectedTarget).equals(SyncRequestIdentity.fingerprint(request,expectedTarget)))
            throw new IllegalStateException("Completed membership group child differs from frozen identity");
        var json=JobDefinitionJson.mapper();var plan=json.readTree(IndexMembershipSourceEvidence.bounded(folder(run).resolve("batch-plan.json"),1024*1024));
        var identity=json.treeToValue(plan.path("initialIdentity"),IndexMembershipStorage.Identity.class);
        String fingerprint=plan.path("initialFingerprint").asText();
        if(!plan.path("runId").asText().equals(run) || !plan.path("table").asText().equals(table)
                || !expectedTarget.equals(StaticTargetIdentity.identify(jdbc,table,identity.id(),identity.directory())))
            throw new IllegalStateException("Completed membership group endpoint differs");
        var locks=new DatasetIntervalLock(path);
        if(locks.findOwned(run,DatasetIntervalLock.Scope.allDates("index_member_batch"))!=null
                || locks.findOwned(run,DatasetIntervalLock.Scope.allDates("index_member"))!=null)
            throw new IllegalStateException("Completed membership group locks need reconciliation");
        for(int i=0;i<scopes.size();i++) {
            var entry=ledger.get(slot(run,i));String id=json.readTree(entry.payloadJson()).path("childRunId").asText();
            if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(entry.state()) || !ledger.getRun(id).parentRunId().equals(run))
                throw new IllegalStateException("Membership group industry's ownership or state differs");
            var parameters=new LinkedHashMap<String,Object>(request.parameters());parameters.put("industryCodes",List.of(scopes.get(i).l2Code()));
            var frozen=IndexMembershipJobPlan.definition().freeze(request.mode(),parameters,request.from(),request.to(),request.logicalDate());
            var verified=IndexMembershipCompletedRun.verify(jdbc,path,table,id,frozen);
            if(entry.state()!=verified.state()) throw new IllegalStateException("Membership group slot differs from child");
            var step=step(id);
            if(!step.before().identity().equals(identity) || !step.before().fingerprint().equals(fingerprint))
                throw new IllegalStateException("Membership group publication chain changed");
            identity=step.after().identity();fingerprint=step.after().fingerprint();
        }
        var actual=new IndexMembershipStorage(jdbc,table).snapshot();
        if(!actual.identity().equals(identity) || !actual.fingerprint().equals(fingerprint))
            throw new IllegalStateException("Completed membership group full target changed");
        Path receipt=folder(run).resolve("batch-completion.json");
        var completion=json.readTree(IndexMembershipSourceEvidence.bounded(receipt,1024*1024));
        if(!completion.path("runId").asText().equals(run) || completion.path("members").size()!=scopes.size())
            throw new IllegalStateException("Membership group completion receipt differs");
        return receipt.toString();
    }
    private Step step(String run) throws Exception {
        var json=JobDefinitionJson.mapper();var receipt=folder(run).resolve("completion.json");
        var proof=json.readTree(IndexMembershipSourceEvidence.bounded(receipt,96*1024*1024));
        if(!proof.path("runId").asText().equals(run)) throw new IllegalStateException("Membership child completion belongs to another run");
        var before=json.treeToValue(proof.path("before"),IndexMembershipStorage.Snapshot.class);
        var after=json.treeToValue(proof.path("actual"),IndexMembershipStorage.Snapshot.class);
        verifySnapshot(before);verifySnapshot(after);
        return new Step(before,after);
    }
    private static void verifySnapshot(IndexMembershipStorage.Snapshot snapshot) throws Exception {
        byte[] rows=JobDefinitionJson.mapper().writeValueAsBytes(snapshot.rows());
        if(rows.length!=snapshot.bytes() || !IndexMembershipSourceEvidence.hash(rows).equals(snapshot.fingerprint()))
            throw new IllegalStateException("Membership child snapshot fingerprint differs from physical rows");
    }
    private static String slot(String run,int ordinal) { return run+"-industry-"+ordinal; }
    private static Map<String,?> payload(IndexMembershipCompletedRun.Proof proof) {
        if(proof.state()==SyncRunState.VERIFIED_EMPTY) return Map.of("childRunId",proof.runId(),"sourceComplete",true,
                "returnedRows",0,"submittedRows",0,"responseEvidence",proof.receipt());
        return Map.of("childRunId",proof.runId(),"verification",Map.of("passed",true,"expectedRows",proof.sourceRows(),
                "actualRows",proof.sourceRows(),"matchedRows",proof.sourceRows(),"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,
                "readbackEvidence",proof.receipt(),"sourceFingerprint",proof.scopeFingerprint()));
    }
    private static void move(SyncRunLedger ledger,String id,SyncRunState state,Map<String,?> proof) throws Exception {
        ledger.transition(id,ledger.get(id).revision(),state,JobDefinitionJson.mapper().writeValueAsString(proof));
    }
}
