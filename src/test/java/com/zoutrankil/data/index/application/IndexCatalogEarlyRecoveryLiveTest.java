package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.storage.QuestDbIndexCatalogTarget;

import com.zoutrankil.data.index.storage.IndexCatalogStaging;
import com.zoutrankil.data.index.storage.IndexCatalogStorage;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class IndexCatalogEarlyRecoveryLiveTest {
    @Test void fullStageIsReusedAndIncompleteStageIsPreservedBeforeFreshRebuild() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            for(boolean complete:List.of(true,false)) {
                String nonce=UUID.randomUUID().toString().replace("-","");String table="java_d003_early_"+nonce;
                Path folder=Path.of("artifacts/java-migration/D003","early-recovery-"+nonce);Files.createDirectories(folder);
                Path path=folder.resolve("ledger.sqlite");var ledger=new SyncRunLedger(path);
                String ddl=" ("+String.join(",",IndexCatalogDataset.DEFINITION.columns().stream()
                        .map(c->c.storageName()+" "+c.storageType().name()).toList())+") TIMESTAMP(import_time) PARTITION BY MONTH WAL";
                jdbc.execute("CREATE TABLE "+table+ddl);var owner=new IndexCatalogJobService(new QuestDbIndexCatalogTarget(jdbc,table),path);
                Path sourceFile=Path.of("artifacts/java-migration/D003/source-catalog.csv");
                var request=owner.plan(sourceFile,LocalDate.of(2026,9,29));String run="catalog-early-"+nonce;
                String target=owner.targetId();ledger.createRun(run,null,target,request);
                ledger.transition(run,0,SyncRunState.RUNNING,"{}");
                ledger.createChild(run+"-attempt",SyncRunLedger.Kind.ATTEMPT,run,run);
                ledger.transition(run+"-attempt",0,SyncRunState.RUNNING,"{}");
                ledger.createChild(run+"-snapshot",SyncRunLedger.Kind.SLICE,run,run+"-attempt");
                ledger.transition(run+"-snapshot",0,SyncRunState.RUNNING,"{}");
                var locks=new DatasetIntervalLock(path);var lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("index"));
                var source=new IndexCatalogFileSource().read(sourceFile,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
                var prepared=IndexCatalogStaging.prepare(new IndexCatalogStorage(jdbc,table).snapshot(),source.rows());
                Path evidence=path.toAbsolutePath().getParent().resolve("sync-evidence").resolve(run);Files.createDirectories(evidence);
                Files.writeString(evidence.resolve("prepared.json"),json.writeValueAsString(Map.of("runId",run,"targetId",target,
                        "request",SyncRequestIdentity.snapshotJson(request),"source",source,"prepared",prepared)));
                String stage;
                if(complete) stage=new IndexCatalogStaging(jdbc).write(prepared,evidence,()->false).table();
                else {
                    stage="java_index_catalog_stage_"+nonce;
                    Files.writeString(evidence.resolve(stage+"-intent.json"),json.writeValueAsString(Map.of("stage",stage,
                            "before",prepared.before(),"rows",prepared.rows())));
                    jdbc.execute("CREATE TABLE "+stage+ddl);
                }
                // Test producer is now stopped; durable state represents an interruption before publication intent.
                for(String id:List.of(run+"-snapshot",run+"-attempt",run))
                    ledger.transition(id,ledger.get(id).revision(),SyncRunState.IN_DOUBT,"{}");
                locks.retainInDoubt(lease);
                assertThrows(IllegalStateException.class,()->owner.finishInterrupted(run,false));
                var recovered=owner.finishInterrupted(run,true);assertEquals(SyncRunState.VERIFIED,recovered.state());
                var actual=new IndexCatalogStorage(jdbc,table).snapshot();assertEquals(prepared.rows(),actual.rows());
                var publication=new ReferencePublicationJournal(path,"index").forRun(run);
                if(complete) assertEquals(stage,publication.intent().stage());
                else { assertNotEquals(stage,publication.intent().stage());assertTrue(new IndexCatalogStorage(jdbc,stage).snapshot().rows().isEmpty()); }
                assertNull(locks.findOwned(run,lease.scope()));
                json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("early-recovery-readback.json").toFile(),
                        Map.of("completeStageInitially",complete,"initialStage",stage,"result",recovered,"actual",actual,
                                "publication",publication,"writerStoppedProof","Test producer completed before recovery"));
                jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+publication.intent().backup());
                if(!complete) jdbc.execute("DROP TABLE "+stage);
            }
        }
    }
}
