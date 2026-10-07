package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.domain.IndexMembershipState;
import com.zoutrankil.data.index.port.IndexMembershipTarget;



import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Reuse an exact owned stage, or rebuild into a fresh stage; never append to a partial stage. */
final class IndexMembershipStageRecovery {
    private IndexMembershipStageRecovery() {}
    static ReferencePublicationJournal.Entry prepare(IndexMembershipTarget backend,Path path,String table,String run,
            Path folder,IndexMembershipState.Prepared prepared,DatasetIntervalLock.Lease lease) throws Exception {
        var journal=new ReferencePublicationJournal(path,"index_member");journal.requireLease(lease,true);
        var before=backend.open(table).snapshot();
        if(!before.equals(prepared.before())) throw new IllegalStateException("Original membership catalog changed before stage recovery");
        String target=backend.identify(table,before.identity().id(),before.identity().directory());
        if(!target.equals(SyncRunLedger.openReadOnly(path).getRun(run).targetId()))
            throw new IllegalStateException("Stage recovery endpoint differs from frozen target");
        var json=JobDefinitionJson.mapper();List<Path> intents;
        try(var paths=Files.list(folder)) {
            intents=paths.filter(p->p.getFileName().toString().matches("java_index_member_stage_[0-9a-f]{32}-intent\\.json"))
                    .limit(9).sorted().toList();
        }
        if(intents.size()>8) throw new IllegalStateException("Stage recovery attempt bound reached");
        String selected=null;IndexMembershipState.Snapshot replacement=null;var retained=new ArrayList<String>();
        for(Path intent:intents) {
            if(Files.size(intent)>96L*1024*1024) throw new IllegalStateException("Stage intent exceeds bound");
            var proof=json.readTree(FileEvidenceStore.readBounded(intent, 96 * 1024 * 1024, () -> new IllegalStateException("Stage intent exceeds bound")));String stage=proof.path("stage").asText();
            if(!intent.getFileName().toString().equals(stage+"-intent.json")
                    || !prepared.equals(json.treeToValue(proof.path("prepared"),IndexMembershipState.Prepared.class)))
                throw new IllegalStateException("Stage intent differs from frozen preparation");
            if(!backend.exists(stage)) continue;
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(!backend.walSettled(stage)) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Retained stage WAL unresolved");Thread.sleep(50);
            }
            var snapshot=backend.open(stage).snapshot();
            if(snapshot.rows().equals(prepared.rows()) && selected==null) { selected=stage;replacement=snapshot; }
            else retained.add(stage);
        }
        boolean rebuilt=selected==null;
        if(rebuilt) {
            var stage=backend.newStaging().write(prepared,folder,()->Thread.currentThread().isInterrupted());
            selected=stage.table();replacement=stage.snapshot();
        }
        if(!backend.open(table).snapshot().equals(before))
            throw new IllegalStateException("Original membership catalog changed during stage recovery");
        FileEvidenceStore.writeNewUtf8(folder.resolve("stage-recovery-"+UUID.randomUUID()+".json"),json.writeValueAsString(
                Map.of("runId",run,"selectedStage",selected,"rebuiltFreshStage",rebuilt,
                        "stageRowsSubmitted",rebuilt?prepared.rows().size():0,"retainedStages",retained,
                        "writerStopped",true,"sourceRequests",0)));
        journal.requireLease(lease,true);
        return journal.create(new ReferencePublicationJournal.Intent("membership-publication-"+UUID.randomUUID(),"index_member",run,
                table,"java_index_member_backup_"+UUID.randomUUID().toString().replace("-",""),selected,target,
                before.identity().id(),before.identity().directory(),replacement.identity().id(),before.fingerprint(),replacement.fingerprint()));
    }
}
