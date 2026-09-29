package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;

/** Recover complete empty or unchanged observations without any QuestDB writes. */
final class IndexMembershipNoWriteRecovery {
    private IndexMembershipNoWriteRecovery() {}
    static IndexMembershipSliceJob.Result finish(JdbcTemplate jdbc,Path path,String table,String run,Path folder,
            SyncJobRunner.Page<IndexMembership> source,IndexMembershipSource.Scope scope,IndexMembershipStaging.Prepared prepared,
            DatasetIntervalLock.Lease lease) throws Exception {
        var ledger=new SyncRunLedger(path);var json=JobDefinitionJson.mapper();
        var actual=new IndexMembershipStorage(jdbc,table).snapshot();
        if(prepared.merge().requiresWrite() || !actual.equals(prepared.before()) || !actual.rows().equals(prepared.rows())
                || !ledger.getRun(run).targetId().equals(StaticTargetIdentity.identify(jdbc,table,actual.identity().id(),actual.identity().directory()))
                || new ReferencePublicationJournal(path,"index_member").findForRun(run).isPresent())
            throw new IllegalStateException("No-write membership target differs from frozen original");
        try(var files=Files.list(folder)) {
            if(files.anyMatch(p->p.getFileName().toString().matches("java_index_member_stage_[0-9a-f]{32}-intent\\.json")))
                throw new IllegalStateException("Unexpected stage intent for no-write observation");
        }
        int count=source.rows().size();var state=count==0?SyncRunState.VERIFIED_EMPTY:SyncRunState.VERIFIED;
        var ids=List.of(run+"-"+scope.l2Code(),run+"-attempt",run);
        for(String id:ids) {
            var entry=ledger.get(id);
            if(!entry.runId().equals(run)) throw new IllegalStateException("Membership recovery ownership differs");
            if(entry.state()!=state) entry.state().requireTransition(state);
        }
        Path receipt=folder.resolve("completion.json");var proof=new LinkedHashMap<String,Object>();
        proof.put("runId",run);proof.put("scope",scope);proof.put("source",source);proof.put("before",prepared.before());proof.put("actual",actual);
        proof.put("merge",prepared.merge());proof.put("publicationId",null);proof.put("submittedStageRows",0);
        proof.put("sourceComplete",true);proof.put("checkpointBefore",actual.fingerprint());proof.put("checkpointAfter",actual.fingerprint());
        if(Files.exists(receipt)) {
            if(!json.readTree(IndexMembershipSourceEvidence.bounded(receipt,96*1024*1024)).equals(json.readTree(json.writeValueAsBytes(proof))))
                throw new IllegalStateException("No-write membership completion receipt differs");
        } else Files.writeString(receipt,json.writeValueAsString(proof),StandardOpenOption.CREATE_NEW);
        Map<String,?> payload;
        if(count==0) payload=Map.of("sourceComplete",true,"returnedRows",0,"submittedRows",0,
                "responseEvidence",source.responseEvidence(),"readbackEvidence",receipt.toString(),"checkpoint",actual.fingerprint());
        else payload=Map.of("evidence",receipt.toString(),"checkpoint",actual.fingerprint(),"verification",
                Map.of("passed",true,"expectedRows",count,"actualRows",count,"matchedRows",count,
                        "mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,"readbackEvidence",receipt.toString(),
                        "sourceFingerprint",source.sourceFingerprint(),"writerStopped",true));
        for(String id:ids) {
            var entry=ledger.get(id);
            if(entry.state()!=state) ledger.transition(id,entry.revision(),state,json.writeValueAsString(payload));
        }
        var locks=new DatasetIntervalLock(path);var current=locks.findOwned(run,lease.scope());
        if(current==null) throw new IllegalStateException("No-write membership lease disappeared");
        if(current.inDoubt()) locks.releaseAfterReconciliation(current,true,true);else locks.releaseVerified(current);
        return new IndexMembershipSliceJob.Result(run,state,count,0,0,prepared.merge().unchanged(),0,receipt.toString(),null);
    }
}
