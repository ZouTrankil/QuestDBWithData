package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Reuse an exact owned stage, or rebuild into a fresh stage; never append to a partial stage. */
final class IndexCatalogStageRecovery {
    private IndexCatalogStageRecovery() {}
    static ReferencePublicationJournal.Entry prepare(JdbcTemplate jdbc,Path path,String table,String run,
            Path folder,IndexCatalogStaging.Prepared prepared,DatasetIntervalLock.Lease lease) throws Exception {
        var journal=new ReferencePublicationJournal(path,"index");journal.requireLease(lease,true);
        var before=new IndexCatalogStorage(jdbc,table).snapshot();
        if(!before.equals(prepared.before())) throw new IllegalStateException("Original catalog changed before stage recovery");
        String target=StaticTargetIdentity.identify(jdbc,table,before.identity().id(),before.identity().directory());
        if(!target.equals(SyncRunLedger.openReadOnly(path).getRun(run).targetId()))
            throw new IllegalStateException("Stage recovery endpoint differs from frozen target");
        var json=JobDefinitionJson.mapper();List<Path> intents;
        try(var paths=Files.list(folder)) {
            intents=paths.filter(p->p.getFileName().toString().matches("java_index_catalog_stage_[0-9a-f]{32}-intent\\.json"))
                    .limit(9).sorted().toList();
        }
        if(intents.size()>8) throw new IllegalStateException("Stage recovery attempt bound reached");
        String selected=null;IndexCatalogStorage.Snapshot replacement=null;var retained=new ArrayList<String>();
        for(Path intent:intents) {
            if(Files.size(intent)>32L*1024*1024) throw new IllegalStateException("Stage intent exceeds bound");
            var proof=json.readTree(intent.toFile());String stage=proof.path("stage").asText();
            if(!intent.getFileName().toString().equals(stage+"-intent.json")
                    || !prepared.before().equals(json.treeToValue(proof.path("before"),IndexCatalogStorage.Snapshot.class))
                    || !prepared.rows().equals(Arrays.asList(json.treeToValue(proof.path("rows"),
                            com.zoutrankil.data.domain.table.IndexRow[].class))))
                throw new IllegalStateException("Stage intent differs from frozen preparation");
            if(jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",stage).isEmpty()) continue;
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(!QuestDbWriteChecks.walSettled(jdbc,stage)) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Retained stage WAL unresolved");Thread.sleep(50);
            }
            var snapshot=new IndexCatalogStorage(jdbc,stage).snapshot();
            if(snapshot.rows().equals(prepared.rows()) && selected==null) { selected=stage;replacement=snapshot; }
            else retained.add(stage);
        }
        boolean rebuilt=selected==null;
        if(rebuilt) {
            var stage=new IndexCatalogStaging(jdbc).write(prepared,folder,()->Thread.currentThread().isInterrupted());
            selected=stage.table();replacement=stage.snapshot();
        }
        if(!new IndexCatalogStorage(jdbc,table).snapshot().equals(before))
            throw new IllegalStateException("Original catalog changed during stage recovery");
        Files.writeString(folder.resolve("stage-recovery-"+UUID.randomUUID()+".json"),json.writeValueAsString(
                Map.of("runId",run,"selectedStage",selected,"rebuiltFreshStage",rebuilt,
                        "stageRowsSubmitted",rebuilt?prepared.rows().size():0,"retainedStages",retained,
                        "writerStopped",true,"sourceRequests",0)),StandardOpenOption.CREATE_NEW);
        journal.requireLease(lease,true);
        return journal.create(new ReferencePublicationJournal.Intent("catalog-publication-"+UUID.randomUUID(),"index",run,
                table,"java_index_catalog_backup_"+UUID.randomUUID().toString().replace("-",""),selected,target,
                before.identity().id(),before.identity().directory(),replacement.identity().id(),before.fingerprint(),replacement.fingerprint()));
    }
}
