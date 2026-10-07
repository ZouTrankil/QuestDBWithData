package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;

/** Reconcile a stopped local prepared writer from its frozen rows and retained physical stage. */
final class IndexMembershipPreparedRecovery {
    private IndexMembershipPreparedRecovery() {}
    static IndexMembershipPreparedJob.Result finish(JdbcTemplate jdbc,Path path,String table,String run,boolean writerStopped)
            throws Exception {
        if(!writerStopped) throw new IllegalStateException("Stopped prepared membership writer proof required");
        if(table.equals("index_member")) throw new IllegalStateException("Formal membership cutover is not admitted");
        path=path.toAbsolutePath().normalize();var ledger=new SyncRunLedger(path);var saved=ledger.getRun(run);
        if(!saved.jobId().equals("write.index_member") || saved.jobVersion()!=1
                || !Set.of(SyncRunState.RUNNING,SyncRunState.IN_DOUBT).contains(ledger.get(run).state()))
            throw new IllegalStateException("Unfinished prepared membership run required");
        var request=IndexMembershipPreparedJob.restore(saved.frozenJson());
        var locks=new DatasetIntervalLock(path);var lease=locks.findOwned(run,DatasetIntervalLock.Scope.allDates("index_member"));
        if(lease==null) throw new IllegalStateException("Retained membership writer lease required");
        String attempt=run+"-attempt",slice=run+"-prepared";
        var a=ledger.get(attempt);var s=ledger.get(slice);
        if(a.kind()!=SyncRunLedger.Kind.ATTEMPT || !a.runId().equals(run) || !a.parentId().equals(run)
                || s.kind()!=SyncRunLedger.Kind.SLICE || !s.runId().equals(run) || !s.parentId().equals(attempt))
            throw new IllegalStateException("Prepared membership run hierarchy changed");
        for(String id:List.of(slice,attempt,run))
            if(!Set.of(SyncRunState.RUNNING,SyncRunState.IN_DOUBT,SyncRunState.VERIFIED).contains(ledger.get(id).state()))
                throw new IllegalStateException("Prepared membership ledger state cannot be recovered");
        Path folder=path.getParent().resolve("sync-evidence").resolve(run);var json=JobDefinitionJson.mapper();
        var frozen=json.readTree(IndexMembershipSourceEvidence.bounded(folder.resolve("prepared.json"),96*1024*1024));
        if(!frozen.path("runId").asText().equals(run) || !frozen.path("targetId").asText().equals(saved.targetId())
                || !frozen.path("request").asText().equals(saved.frozenJson())
                || !frozen.path("sourceKind").asText().equals("prepared-write-request"))
            throw new IllegalStateException("Prepared membership evidence differs from frozen writer");
        var prepared=json.treeToValue(frozen.path("prepared"),IndexMembershipStaging.Prepared.class);
        if(!prepared.l2Code().startsWith("prepared:") || !prepared.equals(IndexMembershipStaging.preparePrepared(
                prepared.before(),prepared.source(),prepared.l2Code().substring(9))))
            throw new IllegalStateException("Prepared membership merge changed");
        Path source=Path.of(frozen.path("sourceReceipt").asText());
        IndexMembershipPreparedJob.validate(request,prepared.source(),source);
        IndexMembershipPreparedJob.requireReceipt(request,prepared.source(),source,saved.targetId());
        String sourceHash=IndexMembershipSourceEvidence.hash(IndexMembershipSourceEvidence.bounded(source,IndexMembershipStorage.MAX_BYTES));
        if(!sourceHash.equals(frozen.path("sourceHash").asText()))
            throw new IllegalStateException("Prepared membership receipt bytes changed");
        byte[] beforeBytes=json.writeValueAsBytes(prepared.before().rows());
        if(!IndexMembershipSourceEvidence.hash(beforeBytes).equals(prepared.before().fingerprint())
                || beforeBytes.length!=prepared.before().bytes()
                || !saved.targetId().equals(StaticTargetIdentity.identify(jdbc,table,
                        prepared.before().identity().id(),prepared.before().identity().directory())))
            throw new IllegalStateException("Prepared membership before snapshot changed");
        IndexMembershipStorage.Snapshot actual;String publication=null;
        if(prepared.merge().requiresWrite()) {
            var journal=new ReferencePublicationJournal(path,"index_member");var existing=journal.findForRun(run);
            var entry=existing.isPresent()?existing.get()
                    :IndexMembershipStageRecovery.prepare(jdbc,path,table,run,folder,prepared,lease);
            var intent=entry.intent();
            if(!intent.target().equals(table) || !intent.initialTarget().equals(saved.targetId())
                    || intent.originalId()!=prepared.before().identity().id()
                    || !intent.beforeFingerprint().equals(prepared.before().fingerprint())
                    || !intent.afterFingerprint().equals(IndexMembershipSourceEvidence.hash(json.writeValueAsBytes(prepared.rows()))))
                throw new IllegalStateException("Prepared membership publication intent differs");
            var publisher=new IndexMembershipPublication(jdbc,path);
            if(publisher.inspect(run)==IndexMembershipPublication.Layout.CONFLICT)
                throw new IllegalStateException("Prepared membership publication layout conflicts");
            actual=publisher.finish(lease,true).actual();publication=intent.id();
            if(!new IndexMembershipStorage(jdbc,intent.backup()).snapshot().equals(prepared.before()))
                throw new IllegalStateException("Prepared membership backup differs after recovery");
        } else {
            actual=new IndexMembershipStorage(jdbc,table).snapshot();
            if(!actual.equals(prepared.before())) throw new IllegalStateException("No-write prepared membership target drifted");
        }
        if(!actual.rows().equals(prepared.rows())) throw new IllegalStateException("Recovered prepared membership rows differ");
        var proof=new LinkedHashMap<String,Object>();
        proof.put("runId",run);proof.put("sourceKind","prepared-write-request");proof.put("sourceReceipt",source.toString());
        proof.put("sourceHash",sourceHash);proof.put("before",prepared.before());proof.put("actual",actual);
        proof.put("prepared",prepared);proof.put("publicationId",publication);
        proof.put("submittedStageRows",prepared.merge().requiresWrite()?prepared.rows().size():0);
        Path completed=folder.resolve("completion.json");
        if(Files.exists(completed)) {
            if(!json.readTree(IndexMembershipSourceEvidence.bounded(completed,96*1024*1024)).equals(json.valueToTree(proof)))
                throw new IllegalStateException("Existing prepared membership completion differs");
        } else FileEvidenceStore.writeNewUtf8(completed,json.writeValueAsString(proof));
        int count=prepared.source().size();
        var verification=Map.of("passed",true,"expectedRows",count,"actualRows",count,"matchedRows",count,
                "mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,"readbackEvidence",completed.toString(),
                "sourceFingerprint",sourceHash,"writerStopped",true);
        var payload=Map.of("verification",verification,"checkpoint",actual.fingerprint(),"evidence",completed.toString());
        for(String id:List.of(slice,attempt,run)) {
            var current=ledger.get(id);
            if(current.state()!=SyncRunState.VERIFIED)
                ledger.transition(id,current.revision(),SyncRunState.VERIFIED,json.writeValueAsString(payload));
        }
        var current=locks.findOwned(run,lease.scope());
        if(current==null) throw new IllegalStateException("Prepared membership lease disappeared");
        if(current.inDoubt()) locks.releaseAfterReconciliation(current,true,true);else locks.releaseVerified(current);
        return new IndexMembershipPreparedJob.Result(run,SyncRunState.VERIFIED,count,count,completed.toString(),null);
    }
}
