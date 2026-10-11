package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.factor.storage.GlobalObservationWriteSession;
import com.zoutrankil.data.factor.storage.QuestDbGlobalObservations;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Reconciles only a durable submitted prefix; remaining pages must use ordinary resume. */
public final class FactorObservationRecovery {
    private FactorObservationRecovery() {}
    private record Page(SyncRunLedger.Entry entry,int start,int end,String fingerprint) {}

    public static Map<String,Object> reconcile(Path ledgerPath,String runId,Path evidence,Path writerLog,
            Path proofPath,GlobalObservationWriteSession session,QuestDbGlobalObservations observations) throws Exception {
        var json=JobDefinitionJson.mapper();
        var writer=FactorWriterExitEvidence.inspect(writerLog,runId);
        var snapshot=FactorObservationEvidence.read(evidence);
        var ledger=new SyncRunLedger(ledgerPath);
        var run=Objects.requireNonNull(ledger.getRun(runId),"Unknown run");
        var root=json.readTree(run.frozenJson());
        LocalDate from=LocalDate.parse(root.path("from").asText()),to=LocalDate.parse(root.path("to").asText());
        String target=session.targetIdentity();
        int version=root.path("definition").path("version").asInt(-1);
        int pageRows=FactorObservationJobService.pageRows(version);
        var request=FactorObservationJobService.definition(version).freeze(SyncJobDefinition.Mode.valueOf(root.path("mode").asText()),
                Map.of("targetId",target,"factorEvidenceSha256",snapshot.fileSha256()),from,to,
                LocalDate.parse(root.path("logicalDate").asText()));
        if(!"data.factor_observation".equals(run.jobId()) || !target.equals(run.targetId())
                || !SyncRequestIdentity.fingerprint(request,target).equals(SyncRequestIdentity.fingerprint(run.frozenJson(),target)))
            throw new IllegalArgumentException("Frozen factor request, evidence or physical target differs");
        session.preflight();
        var locks=new DatasetIntervalLock(ledgerPath);
        var lease=locks.findOwned(runId,new DatasetIntervalLock.Scope("factor_observation",from,to));
        if(lease==null || !lease.inDoubt())throw new IllegalStateException("Retained uncertain interval lock required");
        var entries=ledger.entries(runId,null,403);
        if(entries.size()>402)throw new IllegalArgumentException("Recovery entry budget exceeded");
        var owners=new ArrayList<SyncRunLedger.Entry>();
        var pages=new ArrayList<Page>();
        for(var entry:entries) {
            if(entry.kind()!=SyncRunLedger.Kind.SLICE) {
                if(entry.state()!=SyncRunState.IN_DOUBT && entry.state()!=SyncRunState.PARTIAL)
                    throw new IllegalStateException("Unexpected parent state: "+entry.state());
                owners.add(entry);continue;
            }
            if(entry.state()!=SyncRunState.VERIFIED && entry.state()!=SyncRunState.IN_DOUBT)
                throw new IllegalStateException("Unresolved non-submitted slice");
            var events=ledger.events(entry.id(),-1,100);
            if(events.size()==100)throw new IllegalArgumentException("Slice event budget exceeded");
            var fetched=events.stream().filter(e->e.state()==SyncRunState.FETCHED).toList();
            if(fetched.size()!=1 || entry.state()==SyncRunState.IN_DOUBT
                    && events.stream().noneMatch(e->e.state()==SyncRunState.SUBMITTED))
                throw new IllegalStateException("Exactly one fetched and durable submitted page required");
            var source=json.readTree(fetched.getFirst().payloadJson());
            int index=Integer.parseInt(source.path("cursor").asText());
            if(index<0 || index>=400)throw new IllegalArgumentException("Invalid page cursor");
            int start=index*pageRows,end=Math.min(start+pageRows,snapshot.rows().size());
            String fingerprint=FileEvidenceStore.sha256(("factor-observation-page-v1\n"+snapshot.fileSha256()+"\n"+start+"\n"+end)
                    .getBytes(StandardCharsets.UTF_8));
            if(end<=start || source.path("returnedRows").asInt(-1)!=end-start
                    || !fingerprint.equals(source.path("sourceFingerprint").asText())
                    || !evidence.toAbsolutePath().normalize().equals(Path.of(source.path("responseEvidence").asText()).toAbsolutePath().normalize()))
                throw new IllegalStateException("Durable page differs from frozen result");
            if(entry.state()==SyncRunState.VERIFIED) {
                var proof=json.readTree(entry.payloadJson()).path("verification");
                if(!proof.path("passed").asBoolean(false) || proof.path("expectedRows").asInt(-1)!=end-start
                        || proof.path("matchedRows").asInt(-1)!=end-start || proof.path("actualRows").asInt(-1)!=end-start
                        || proof.path("missingKeys").asInt(-1)!=0 || proof.path("duplicateKeys").asInt(-1)!=0
                        || proof.path("mismatchedRows").asInt(-1)!=0 || !fingerprint.equals(proof.path("sourceFingerprint").asText()))
                    throw new IllegalStateException("Previously verified or reused page lacks matching proof");
            }
            pages.add(new Page(entry,start,end,fingerprint));
        }
        if(owners.size()!=2 || owners.stream().filter(e->e.kind()==SyncRunLedger.Kind.RUN && e.id().equals(runId)).count()!=1
                || owners.stream().filter(e->e.kind()==SyncRunLedger.Kind.ATTEMPT && runId.equals(e.parentId())).count()!=1)
            throw new IllegalArgumentException("Recovery requires a single publication attempt");
        String attempt=owners.stream().filter(e->e.kind()==SyncRunLedger.Kind.ATTEMPT).findFirst().orElseThrow().id();
        pages.sort(Comparator.comparingInt(Page::start));int submitted=0;
        for(var page:pages) {
            if(page.start()!=submitted || !attempt.equals(page.entry().parentId()))
                throw new IllegalStateException("Submitted pages are not a unique contiguous prefix");
            submitted=page.end();
        }
        if(submitted==0)throw new IllegalStateException("No submitted pages");
        var prefix=snapshot.rows().subList(0,submitted);
        var delta=observations.reconcile(prefix);
        if(!delta.writes().isEmpty() || delta.matching()!=submitted)
            throw new IllegalStateException("Submitted prefix is missing or differs; cannot reconcile");
        requireUnchanged(writer,evidence,snapshot.fileSha256(),session,target);
        Path proof=proofPath.toAbsolutePath().normalize();
        var report=new LinkedHashMap<String,Object>();
        report.put("format","java.factor_recovery.v1");report.put("runId",runId);
        report.put("targetId",target);report.put("evidenceSha256",snapshot.fileSha256());
        report.put("writer",writer);report.put("submittedRows",submitted);report.put("expectedRows",snapshot.rows().size());
        report.put("pages",pages.stream().map(p->Map.of("sliceId",p.entry().id(),"start",p.start(),"end",p.end(),"fingerprint",p.fingerprint())).toList());
        report.put("verification",verification(submitted,snapshot.fileSha256(),proof.toString()));
        FileEvidenceStore.writeNew(proof,json.writeValueAsBytes(report));
        for(var page:pages)if(page.entry().state()==SyncRunState.IN_DOUBT) {
            writer.requireStopped();
            ledger.transition(page.entry().id(),page.entry().revision(),SyncRunState.VERIFIED,
                    json.writeValueAsString(Map.of("verification",verification(page.end()-page.start(),page.fingerprint(),proof.toString()))));
        }
        // Attempt before run. A rerun after a crash accepts already reconciled children/parents.
        owners.sort(Comparator.comparing(e->e.kind()==SyncRunLedger.Kind.RUN ? 1:0));
        for(var owner:owners)if(owner.state()==SyncRunState.IN_DOUBT) {
            ledger.transition(owner.id(),owner.revision(),SyncRunState.PARTIAL,json.writeValueAsString(Map.of(
                    "submittedRows",submitted,"sourceComplete",false,"allSubmittedSlicesVerified",true,
                    "verification",verification(submitted,snapshot.fileSha256(),proof.toString()))));
        }
        var finalRead=observations.reconcile(prefix);
        if(!finalRead.writes().isEmpty() || finalRead.matching()!=submitted)throw new IllegalStateException("Final readback changed; lock retained");
        requireUnchanged(writer,evidence,snapshot.fileSha256(),session,target);
        locks.releaseAfterReconciliation(lease,true,true);
        return Map.of("runId",runId,"state","PARTIAL","verifiedRows",submitted,
                "remainingRows",snapshot.rows().size()-submitted,"lockReleased",true,"proof",proof.toString());
    }
    private static void requireUnchanged(FactorWriterExitEvidence writer,Path evidence,String sha,
            GlobalObservationWriteSession session,String target) throws Exception {
        writer.requireStopped();
        if(!sha.equals(FactorObservationEvidence.read(evidence).fileSha256()) || !target.equals(session.targetIdentity()))
            throw new IllegalStateException("Recovery evidence or target changed");
    }
    private static Map<String,Object> verification(int rows,String fingerprint,String proof) {
        return Map.of("passed",true,"writerStopped",true,"expectedRows",rows,"matchedRows",rows,"actualRows",rows,
                "missingKeys",0,"duplicateKeys",0,"mismatchedRows",0,"sourceFingerprint",fingerprint,"readbackEvidence",proof);
    }
}
