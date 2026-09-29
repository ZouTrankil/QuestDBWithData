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
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class IndexCatalogNoWriteRecoveryLiveTest {
    @Test void interruptedUnchangedAndEmptyObservationsFinishByReadingExistingTarget() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            String nonce=UUID.randomUUID().toString().replace("-","");String table="java_d003_no_write_"+nonce;
            Path folder=Path.of("artifacts/java-migration/D003","no-write-recovery-"+nonce);Files.createDirectories(folder);
            Path path=folder.resolve("ledger.sqlite");var ledger=new SyncRunLedger(path);
            jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",IndexCatalogDataset.DEFINITION.columns().stream()
                    .map(c->c.storageName()+" "+c.storageType().name()).toList())+") TIMESTAMP(import_time) PARTITION BY MONTH WAL");
            var owner=new IndexCatalogJobService(jdbc,path,table);var day=LocalDate.of(2026,9,29);
            Path full=Path.of("artifacts/java-migration/D003/source-catalog.csv"),empty=folder.resolve("empty.csv");
            Files.writeString(empty,String.join(",",IndexCatalogFileSource.HEADERS)+"\n");
            var first=owner.run(owner.plan(full,day));assertEquals(SyncRunState.VERIFIED,first.state());
            var original=new IndexCatalogStorage(jdbc,table).snapshot();var results=new ArrayList<IndexCatalogJobService.Result>();
            for(Path file:List.of(full,empty)) {
                var request=owner.plan(file,day);String run="catalog-no-write-"+UUID.randomUUID();String target=owner.targetId();
                ledger.createRun(run,null,target,request);ledger.transition(run,0,SyncRunState.RUNNING,"{}");
                ledger.createChild(run+"-attempt",SyncRunLedger.Kind.ATTEMPT,run,run);ledger.transition(run+"-attempt",0,SyncRunState.RUNNING,"{}");
                ledger.createChild(run+"-snapshot",SyncRunLedger.Kind.SLICE,run,run+"-attempt");ledger.transition(run+"-snapshot",0,SyncRunState.RUNNING,"{}");
                var locks=new DatasetIntervalLock(path);var lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("index"));
                var source=new IndexCatalogFileSource().read(file,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
                var prepared=IndexCatalogStaging.prepare(original,source.rows());assertFalse(prepared.merge().requiresWrite());
                Path evidence=path.toAbsolutePath().getParent().resolve("sync-evidence").resolve(run);Files.createDirectories(evidence);
                Files.writeString(evidence.resolve("prepared.json"),json.writeValueAsString(Map.of("runId",run,"targetId",target,
                        "request",SyncRequestIdentity.snapshotJson(request),"source",source,"prepared",prepared)));
                // Simulated stopped process after durable preparation, before no-write completion.
                assertThrows(IllegalStateException.class,()->owner.finishInterrupted(run,false));
                var result=owner.finishInterrupted(run,true);results.add(result);
                var expected=source.rows().isEmpty()?SyncRunState.VERIFIED_EMPTY:SyncRunState.VERIFIED;
                assertEquals(expected,result.state());assertNull(result.publicationId());
                for(String id:List.of(run,run+"-attempt",run+"-snapshot")) assertEquals(expected,ledger.get(id).state());
                assertEquals(original,new IndexCatalogStorage(jdbc,table).snapshot());assertNull(locks.findOwned(run,lease.scope()));
            }
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("no-write-recovery-readback.json").toFile(),
                    Map.of("initial",first,"recovered",results,"actual",original,"recoveryRowInserts",0,"recoveryRenames",0));
            String backup=new ReferencePublicationJournal(path,"index").forRun(first.runId()).intent().backup();
            jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+backup);
        }
    }
}
