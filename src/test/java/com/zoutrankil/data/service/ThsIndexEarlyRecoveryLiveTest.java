package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class ThsIndexEarlyRecoveryLiveTest {
    @Test void completeStageIsReusedAndIncompleteStageRetainedBeforeFreshRebuild() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            var production=new ThsIndexStorage(jdbc,"ths_index").snapshot();
            Path retained=Path.of("artifacts/java-migration/D004/source-preflight.json");
            byte[] retainedBytes=Files.readAllBytes(retained);var saved=json.readTree(retainedBytes);
            assertTrue(saved.path("fullResponseBelowLimit").asBoolean());
            List<Map<String,JsonNode>> raw=json.convertValue(saved.path("rows"),new com.fasterxml.jackson.core.type.TypeReference<>() {});
            var replay=new TusharePageService(null) {
                @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> params,
                        PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                    return new PageExecutor().execute(contract,params,p->new PageExecutor.Page(raw,null,false,null),consumer,validator,cancelled);
                }
            };
            for(boolean complete:List.of(true,false)) {
                String nonce=UUID.randomUUID().toString().replace("-","");String table="java_d004_early_"+nonce;
                Path folder=Path.of("artifacts/java-migration/D004","early-recovery-"+nonce);Files.createDirectories(folder);
                Path path=folder.resolve("ledger.sqlite");var ledger=new SyncRunLedger(path);
                String ddl=" (ts_code SYMBOL,name STRING,\"count\" INT,exchange STRING,list_date STRING,\"type\" STRING,"
                        +"update_time TIMESTAMP) TIMESTAMP(update_time) PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)";
                jdbc.execute("CREATE TABLE "+table+ddl);var owner=new ThsIndexJobService(jdbc,replay,path,table);
                var request=owner.plan(LocalDate.of(2026,9,29));String run="ths-early-"+nonce,target=owner.targetId();
                ledger.createRun(run,null,target,request);ledger.transition(run,0,SyncRunState.RUNNING,"{}");
                ledger.createChild(run+"-attempt",SyncRunLedger.Kind.ATTEMPT,run,run);ledger.transition(run+"-attempt",0,SyncRunState.RUNNING,"{}");
                ledger.createChild(run+"-snapshot",SyncRunLedger.Kind.SLICE,run,run+"-attempt");ledger.transition(run+"-snapshot",0,SyncRunState.RUNNING,"{}");
                var locks=new DatasetIntervalLock(path);var lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("ths_index"));
                Path evidence=path.toAbsolutePath().getParent().resolve("sync-evidence").resolve(run);Files.createDirectories(evidence);
                var source=new ThsIndexSource(replay,evidence).fetch(ThsIndexSource.Scope.all(),Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
                var prepared=ThsIndexStaging.prepare(new ThsIndexStorage(jdbc,table).snapshot(),source.rows(),ThsIndexSource.Scope.all());
                Files.writeString(evidence.resolve("prepared.json"),json.writeValueAsString(Map.of("runId",run,"targetId",target,
                        "request",SyncRequestIdentity.snapshotJson(request),"source",source,"prepared",prepared)));
                String stage;
                if(complete) stage=new ThsIndexStaging(jdbc).write(prepared,evidence,()->false).table();
                else {
                    stage="java_ths_index_stage_"+nonce;
                    Files.writeString(evidence.resolve(stage+"-intent.json"),json.writeValueAsString(Map.of("stage",stage,"prepared",prepared)));
                    jdbc.execute("CREATE TABLE "+stage+ddl);
                }
                // Producer stopped here; this represents a durable pre-publication interruption, not an OS kill.
                for(String id:List.of(run+"-snapshot",run+"-attempt",run)) ledger.transition(id,ledger.get(id).revision(),SyncRunState.IN_DOUBT,"{}");
                locks.retainInDoubt(lease);
                assertThrows(IllegalStateException.class,()->owner.finishInterrupted(run,false));
                var result=owner.finishInterrupted(run,true);assertEquals(SyncRunState.VERIFIED,result.state());
                var actual=new ThsIndexStorage(jdbc,table).snapshot();assertEquals(prepared.rows(),actual.rows());
                var publication=new ReferencePublicationJournal(path,"ths_index").forRun(run);
                if(complete) assertEquals(stage,publication.intent().stage());
                else { assertNotEquals(stage,publication.intent().stage());assertTrue(new ThsIndexStorage(jdbc,stage).snapshot().rows().isEmpty()); }
                assertNull(locks.findOwned(run,lease.scope()));
                for(String id:List.of(run+"-snapshot",run+"-attempt",run)) assertEquals(SyncRunState.VERIFIED,ledger.get(id).state());
                assertEquals(production,new ThsIndexStorage(jdbc,"ths_index").snapshot());
                // A second observation has identical business values and must recover without replacing the table.
                String unchangedRun="ths-unchanged-"+nonce,unchangedTarget=owner.targetId();
                ledger.createRun(unchangedRun,null,unchangedTarget,request);ledger.transition(unchangedRun,0,SyncRunState.RUNNING,"{}");
                ledger.createChild(unchangedRun+"-attempt",SyncRunLedger.Kind.ATTEMPT,unchangedRun,unchangedRun);
                ledger.transition(unchangedRun+"-attempt",0,SyncRunState.RUNNING,"{}");
                ledger.createChild(unchangedRun+"-snapshot",SyncRunLedger.Kind.SLICE,unchangedRun,unchangedRun+"-attempt");
                ledger.transition(unchangedRun+"-snapshot",0,SyncRunState.RUNNING,"{}");
                var unchangedLease=locks.acquire(unchangedRun,DatasetIntervalLock.Scope.allDates("ths_index"));
                Path unchangedFolder=evidence.resolveSibling(unchangedRun);Files.createDirectories(unchangedFolder);
                var unchangedSource=new ThsIndexSource(replay,unchangedFolder).fetch(ThsIndexSource.Scope.all(),
                        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
                var unchangedPrepared=ThsIndexStaging.prepare(actual,unchangedSource.rows(),ThsIndexSource.Scope.all());
                assertFalse(unchangedPrepared.merge().requiresWrite());
                Files.writeString(unchangedFolder.resolve("prepared.json"),json.writeValueAsString(Map.of(
                        "runId",unchangedRun,"targetId",unchangedTarget,"request",SyncRequestIdentity.snapshotJson(request),
                        "source",unchangedSource,"prepared",unchangedPrepared)));
                assertThrows(IllegalStateException.class,()->owner.finishInterrupted(unchangedRun,false));
                var unchangedResult=owner.finishInterrupted(unchangedRun,true);
                assertEquals(SyncRunState.VERIFIED,unchangedResult.state());assertNull(unchangedResult.publicationId());
                assertEquals(actual,new ThsIndexStorage(jdbc,table).snapshot());
                assertTrue(new ReferencePublicationJournal(path,"ths_index").findForRun(unchangedRun).isEmpty());
                assertNull(locks.findOwned(unchangedRun,unchangedLease.scope()));
                for(String id:List.of(unchangedRun+"-snapshot",unchangedRun+"-attempt",unchangedRun))
                    assertEquals(SyncRunState.VERIFIED,ledger.get(id).state());
                json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("unchanged-recovery-readback.json").toFile(),
                        Map.of("result",unchangedResult,"actual",actual,"recoveryRowsSubmitted",0,"recoveryRenames",0,
                                "sourceKind","retained real response","writerStoppedProof","Test producer completed before recovery"));
                json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("early-recovery-readback.json").toFile(),Map.of(
                        "completeStageInitially",complete,"initialStage",stage,"result",result,"actual",actual,"publication",publication,
                        "sourceKind","retained real source response, no new HTTP request",
                        "sourceReceipt",retained.toString(),"sourceSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(retainedBytes)),
                        "writerStoppedProof","Test producer completed before recovery","productionUnchanged",true));
                jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+publication.intent().backup());
                if(!complete) jdbc.execute("DROP TABLE "+stage);
            }
        }
    }
}
