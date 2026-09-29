package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;

/** Recover a stopped membership publisher without re-fetching its frozen source. */
public final class IndexMembershipRunRecovery {
    private IndexMembershipRunRecovery() {}
    public static IndexMembershipSliceJob.Result finish(JdbcTemplate jdbc,Path ledgerPath,String table,String runId,
                                                        boolean writerStopped) throws Exception {
        if(!writerStopped) throw new IllegalStateException("Stopped membership writer proof required");
        DatasetDefinition.identifier(table);ledgerPath=ledgerPath.toAbsolutePath().normalize();
        var ledger=new SyncRunLedger(ledgerPath);var run=ledger.getRun(runId);
        if(!run.jobId().equals("data.index_member") || run.jobVersion()!=1
                || !Set.of(SyncRunState.RUNNING,SyncRunState.IN_DOUBT).contains(ledger.get(runId).state()))
            throw new IllegalStateException("Unfinished membership source run required");
        var request=IndexMembershipJobPlan.restore(run.frozenJson());var scopes=IndexMembershipJobPlan.scopes(request);
        if(scopes.size()!=1 || !run.logicalDate().equals(request.logicalDate().toString()))
            throw new IllegalStateException("Single frozen industry and logical day required");
        var scope=scopes.getFirst();
        if(table.equals("index_member") && scope.selection()!=IndexMembershipSource.Selection.CURRENT)
            throw new IllegalStateException("Historical membership recovery requires an isolated target");
        var locks=new DatasetIntervalLock(ledgerPath);var lease=locks.findOwned(runId,DatasetIntervalLock.Scope.allDates("index_member"));
        if(lease==null) throw new IllegalStateException("Retained membership lease required");
        String attempt=runId+"-attempt",slice=runId+"-"+scope.l2Code();
        var attemptEntry=ledger.get(attempt);var sliceEntry=ledger.get(slice);
        if(attemptEntry.kind()!=SyncRunLedger.Kind.ATTEMPT || !runId.equals(attemptEntry.parentId())
                || !runId.equals(attemptEntry.runId()) || sliceEntry.kind()!=SyncRunLedger.Kind.SLICE
                || !attempt.equals(sliceEntry.parentId()) || !runId.equals(sliceEntry.runId()))
            throw new IllegalStateException("Membership run/attempt/slice ownership differs");
        for(String id:List.of(slice,attempt,runId)) if(!Set.of(SyncRunState.RUNNING,SyncRunState.IN_DOUBT,SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(ledger.get(id).state()))
            throw new IllegalStateException("Membership ledger state is not recoverable from publication");
        var json=JobDefinitionJson.mapper();Path folder=ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        var frozen=json.readTree(IndexMembershipSourceEvidence.bounded(folder.resolve("prepared.json"),96*1024*1024));
        if(!runId.equals(frozen.path("runId").asText()) || !run.targetId().equals(frozen.path("targetId").asText())
                || !run.frozenJson().equals(frozen.path("request").asText()) || !frozen.path("scope").equals(json.valueToTree(scope)))
            throw new IllegalStateException("Membership prepared evidence differs from frozen run authority");
        SyncJobRunner.Page<IndexMembership> source=json.convertValue(frozen.path("source"),new com.fasterxml.jackson.core.type.TypeReference<>() {});
        IndexMembershipSourceEvidence.verify(source,scope,folder);
        var prepared=json.treeToValue(frozen.path("prepared"),IndexMembershipStaging.Prepared.class);
        if(!prepared.equals(IndexMembershipStaging.prepare(prepared.before(),source.rows(),scope.l2Code())))
            throw new IllegalStateException("Membership merge evidence changed");
        byte[] beforeBytes=json.writeValueAsBytes(prepared.before().rows());
        if(!IndexMembershipSourceEvidence.hash(beforeBytes).equals(prepared.before().fingerprint()) || beforeBytes.length!=prepared.before().bytes())
            throw new IllegalStateException("Membership original fingerprint changed");
        if(!prepared.merge().requiresWrite())
            return IndexMembershipNoWriteRecovery.finish(jdbc,ledgerPath,table,runId,folder,source,scope,prepared,lease);
        for(String id:List.of(slice,attempt,runId)) {
            var state=ledger.get(id).state();if(state!=SyncRunState.VERIFIED) state.requireTransition(SyncRunState.VERIFIED);
        }
        var journal=new ReferencePublicationJournal(ledgerPath,"index_member");var existing=journal.findForRun(runId);
        var entry=existing.isPresent()?existing.get():IndexMembershipStageRecovery.prepare(jdbc,ledgerPath,table,runId,folder,prepared,lease);
        var intent=entry.intent();
        if(!intent.target().equals(table) || !intent.initialTarget().equals(run.targetId())
                || intent.originalId()!=prepared.before().identity().id() || !intent.originalDirectory().equals(prepared.before().identity().directory())
                || !intent.beforeFingerprint().equals(prepared.before().fingerprint())
                || !intent.afterFingerprint().equals(IndexMembershipSourceEvidence.hash(json.writeValueAsBytes(prepared.rows()))))
            throw new IllegalStateException("Membership publication differs from frozen merge");
        var publisher=new IndexMembershipPublication(jdbc,ledgerPath);
        if(publisher.inspect(runId)==IndexMembershipPublication.Layout.CONFLICT)
            throw new IllegalStateException("Conflicting membership publication layout");
        // A pre-existing completion receipt is evidence, not authority to mutate or mark verified.
        Path receipt=folder.resolve("completion.json");
        if(Files.exists(receipt)) {
            var old=json.readTree(IndexMembershipSourceEvidence.bounded(receipt,96*1024*1024));
            if(!old.path("runId").asText().equals(runId) || !old.path("source").equals(json.valueToTree(source))
                    || !prepared.before().equals(json.treeToValue(old.path("before"),IndexMembershipStorage.Snapshot.class))
                    || !old.path("actual").path("rows").equals(json.valueToTree(prepared.rows())))
                throw new IllegalStateException("Existing membership completion evidence changed");
        }
        var completed=publisher.finish(lease,true);var actual=completed.actual();
        if(!actual.rows().equals(prepared.rows()) || !actual.fingerprint().equals(intent.afterFingerprint())
                || !new IndexMembershipStorage(jdbc,intent.backup()).snapshot().equals(prepared.before()))
            throw new IllegalStateException("Recovered membership target or backup differs");
        var proof=new LinkedHashMap<String,Object>();
        proof.put("runId",runId);proof.put("scope",scope);proof.put("source",source);proof.put("before",prepared.before());proof.put("actual",actual);
        proof.put("merge",prepared.merge());proof.put("publicationId",intent.id());proof.put("submittedStageRows",prepared.rows().size());
        proof.put("sourceComplete",true);proof.put("checkpointBefore",prepared.before().fingerprint());proof.put("checkpointAfter",actual.fingerprint());
        if(Files.exists(receipt)) {
            if(!json.readTree(IndexMembershipSourceEvidence.bounded(receipt,96*1024*1024)).equals(json.readTree(json.writeValueAsBytes(proof))))
                throw new IllegalStateException("Membership completion differs from full physical proof");
        } else Files.writeString(receipt,json.writeValueAsString(proof),StandardOpenOption.CREATE_NEW);
        int sourceRows=source.rows().size();
        var verification=Map.of("passed",true,"expectedRows",sourceRows,"actualRows",sourceRows,"matchedRows",sourceRows,
                "mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,"readbackEvidence",receipt.toString(),
                "sourceFingerprint",source.sourceFingerprint(),"writerStopped",true);
        var payload=Map.of("verification",verification,"checkpoint",actual.fingerprint(),"evidence",receipt.toString());
        for(String id:List.of(slice,attempt,runId)) {
            var current=ledger.get(id);
            if(current.state()!=SyncRunState.VERIFIED) ledger.transition(id,current.revision(),SyncRunState.VERIFIED,json.writeValueAsString(payload));
        }
        var current=locks.findOwned(runId,lease.scope());
        if(current==null) throw new IllegalStateException("Membership lease disappeared before reconciliation completed");
        if(current.inDoubt()) locks.releaseAfterReconciliation(current,true,true);else locks.releaseVerified(current);
        var merge=prepared.merge();
        return new IndexMembershipSliceJob.Result(runId,SyncRunState.VERIFIED,sourceRows,merge.inserted(),merge.revised(),
                merge.unchanged(),prepared.rows().size(),receipt.toString(),null);
    }
}
