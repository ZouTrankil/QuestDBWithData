package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;

/** Complete a stopped observation that never needed publication, using an unchanged full target snapshot. */
final class ThsIndexNoWriteRecovery {
    private ThsIndexNoWriteRecovery() {}
    static ThsIndexJobService.Result finish(JdbcTemplate jdbc,Path path,String table,String run,
            Path folder,SyncJobRunner.Page<ThsIndex> source,ThsIndexStaging.Prepared prepared,
            DatasetIntervalLock.Lease lease) throws Exception {
        var ledger=new SyncRunLedger(path);var json=JobDefinitionJson.mapper();
        var actual=new ThsIndexStorage(jdbc,table).snapshot();
        if(prepared.merge().requiresWrite() || !actual.equals(prepared.before()) || !actual.rows().equals(prepared.rows())
                || !ledger.getRun(run).targetId().equals(StaticTargetIdentity.identify(jdbc,table,actual.identity().id(),actual.identity().directory()))
                || new ReferencePublicationJournal(path,"ths_index").findForRun(run).isPresent())
            throw new IllegalStateException("No-write THS observation differs from original target");
        int count=source.rows().size();
        if(count==0) throw new IllegalStateException("Empty THS observation cannot be verified");
        var state=SyncRunState.VERIFIED;
        var ids=List.of(run+"-snapshot",run+"-attempt",run);
        for(String id:ids) {
            var entry=ledger.get(id);
            if(!entry.runId().equals(run)) throw new IllegalStateException("THS recovery ownership differs");
            if(entry.state()!=state) entry.state().requireTransition(state);
        }
        Path receipt=folder.resolve("completion.json");var proof=new LinkedHashMap<String,Object>();
        proof.put("runId",run);proof.put("source",source);proof.put("before",prepared.before());proof.put("actual",actual);
        proof.put("merge",prepared.merge());proof.put("publicationId",null);proof.put("submittedStageRows",0);
        if(Files.exists(receipt)) {
            if(!json.readTree(receipt.toFile()).equals(json.readTree(json.writeValueAsBytes(proof))))
                throw new IllegalStateException("No-write completion receipt differs");
        } else FileEvidenceStore.writeNewUtf8(receipt,json.writeValueAsString(proof));
        var payload=new LinkedHashMap<String,Object>();payload.put("evidence",receipt.toString());payload.put("checkpoint",actual.fingerprint());
        payload.put("verification",Map.of("passed",true,"expectedRows",count,"actualRows",count,"matchedRows",count,
                "mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,"readbackEvidence",receipt.toString(),
                "sourceFingerprint",source.sourceFingerprint(),"writerStopped",true));
        for(String id:ids) {
            var entry=ledger.get(id);
            if(entry.state()!=state) ledger.transition(id,entry.revision(),state,json.writeValueAsString(payload));
        }
        var locks=new DatasetIntervalLock(path);var current=locks.findOwned(run,lease.scope());
        if(current==null) throw new IllegalStateException("No-write recovery lease disappeared");
        if(current.inDoubt()) locks.releaseAfterReconciliation(current,true,true);else locks.releaseVerified(current);
        return new ThsIndexJobService.Result(run,state,count,0,0,0,
                prepared.merge().unchanged(),count,null,receipt.toString(),null);
    }
}
