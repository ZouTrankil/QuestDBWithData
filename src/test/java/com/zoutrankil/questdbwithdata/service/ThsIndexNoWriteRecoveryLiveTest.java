package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class ThsIndexNoWriteRecoveryLiveTest {
    @Test void stoppedUnchangedFullObservationFinishesWithoutSourceReplayOrRename() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            String nonce=UUID.randomUUID().toString().replace("-",""),table="java_d004_no_write_"+nonce;
            Path folder=Path.of("artifacts/java-migration/D004","no-write-recovery-"+nonce);Files.createDirectories(folder);
            Path path=folder.resolve("ledger.sqlite");var ledger=new SyncRunLedger(path);
            jdbc.execute("CREATE TABLE \""+table+"\" (ts_code SYMBOL,name STRING,\"count\" INT,exchange STRING,"
                    +"list_date STRING,\"type\" STRING,update_time TIMESTAMP) TIMESTAMP(update_time) "
                    +"PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)");
            var owner=new ThsIndexJobService(jdbc,context.getBean(TusharePageService.class),path,table);
            var request=owner.plan(LocalDate.of(2026,9,29));
            var first=owner.run(request);assertEquals(SyncRunState.VERIFIED,first.state());
            var original=new ThsIndexStorage(jdbc,table).snapshot();
            var sourceJson=json.readTree(Path.of(first.evidence()).toFile()).path("source");
            var prior=json.readValue(json.writeValueAsBytes(sourceJson),
                    new com.fasterxml.jackson.core.type.TypeReference<SyncJobRunner.Page<ThsIndex>>() {});
            String run="ths-no-write-"+nonce,target=owner.targetId();
            ledger.createRun(run,null,target,request);ledger.transition(run,0,SyncRunState.RUNNING,"{}");
            ledger.createChild(run+"-attempt",SyncRunLedger.Kind.ATTEMPT,run,run);
            ledger.transition(run+"-attempt",0,SyncRunState.RUNNING,"{}");
            ledger.createChild(run+"-snapshot",SyncRunLedger.Kind.SLICE,run,run+"-attempt");
            ledger.transition(run+"-snapshot",0,SyncRunState.RUNNING,"{}");
            var locks=new DatasetIntervalLock(path);var lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("ths_index"));
            assertNotNull(lease);
            Path evidence=path.toAbsolutePath().getParent().resolve("sync-evidence").resolve(run);Files.createDirectories(evidence);
            Path copied=evidence.resolve("source-copy.json");Files.copy(Path.of(prior.responseEvidence()),copied);
            var source=new SyncJobRunner.Page<>(prior.rows(),prior.sourceFingerprint(),copied.toString(),null);
            var prepared=ThsIndexStaging.prepare(original,source.rows(),ThsIndexSource.Scope.all());
            assertFalse(prepared.merge().requiresWrite());
            Files.writeString(evidence.resolve("prepared.json"),json.writeValueAsString(Map.of("runId",run,"targetId",target,
                    "request",SyncRequestIdentity.snapshotJson(request),"source",source,"prepared",prepared)));
            assertThrows(IllegalStateException.class,()->owner.finishInterrupted(run,false));
            var recovered=owner.finishInterrupted(run,true);assertEquals(SyncRunState.VERIFIED,recovered.state());
            assertNull(recovered.publicationId());assertEquals(original,new ThsIndexStorage(jdbc,table).snapshot());
            assertNull(locks.findOwned(run,lease.scope()));
            for(String id:List.of(run,run+"-attempt",run+"-snapshot")) assertEquals(SyncRunState.VERIFIED,ledger.get(id).state());
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("no-write-readback.json").toFile(),
                    Map.of("first",first,"recovered",recovered,"target",original,
                            "sourceRequestsDuringRecovery",0,"rowInsertsDuringRecovery",0,"renamesDuringRecovery",0));
            String backup=new ReferencePublicationJournal(path,"ths_index").forRun(first.runId()).intent().backup();
            jdbc.execute("DROP TABLE \""+table+"\"");jdbc.execute("DROP TABLE \""+backup+"\"");
        }
    }
}
