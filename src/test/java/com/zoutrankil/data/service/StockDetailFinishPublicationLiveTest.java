package com.zoutrankil.data.service;

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
import static com.zoutrankil.data.repository.StockDetailPublicationJournal.State;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class StockDetailFinishPublicationLiveTest {
    static class AbruptStop extends Error {}
    @Test void originalAndOldMovedLayoutsFinishThroughOwnerWithoutSourceReplay() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");
            var folder=Path.of("artifacts/java-migration/D002","finish-publication-"+id);
            var path=Path.of("var","D002-finish-publication-"+id+".sqlite");
            var ledger=new SyncRunLedger(path);var locks=new DatasetIntervalLock(path);
            var jdbc=ctx.getBean(JdbcTemplate.class);var pages=ctx.getBean(TusharePageService.class);
            var observed=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var source=new StockDetailInfoSource(pages,folder).fetch("000001.SZ",observed,()->false);
            assertEquals(1,source.rows().size());var cases=new ArrayList<Object>();
            for(var phase:List.of(State.PREPARED,State.OLD_RENAMED,State.IN_DOUBT)) {
                String run="finish-"+phase.name()+"-"+id;String table="java_d002_finish_"+phase.name().toLowerCase()+"_"+id;
                jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",StockDetailInfoDataset.DEFINITION.columns()
                        .stream().map(c->c.storageName()+" "+c.storageType().name()).toList())+")");
                jdbc.execute("INSERT INTO "+table+" (ts_code,update_time,name) VALUES ('600000.SH',cast(0 AS TIMESTAMP),'retained fixture')");
                var owner=new StockDetailInfoJobService(pages,jdbc,path,table);
                var request=owner.plan(List.of("000001.SZ"),false,LocalDate.of(2026,9,29));
                var before=new StockDetailInfoStorage(jdbc,table).snapshot();String target=owner.targetId();
                ledger.createRun(run,null,target,request);running(ledger,run);
                ledger.createChild(run+"-attempt",SyncRunLedger.Kind.ATTEMPT,run,run);running(ledger,run+"-attempt");
                ledger.createChild(run+"-snapshot",SyncRunLedger.Kind.SLICE,run,run+"-attempt");running(ledger,run+"-snapshot");
                var lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("stock_detail_info"));
                var prepared=StockDetailInfoStaging.prepare(before,source.rows());
                var evidence=path.toAbsolutePath().getParent().resolve("sync-evidence").resolve(run);
                StockDetailRecoveryEvidence.prepare(evidence,run,target,request,observed,source.rows(),List.of(source.responseEvidence()),prepared);
                String partial=null;
                if(phase==State.IN_DOUBT) {
                    partial="java_d002_partial_"+id;
                    jdbc.execute("CREATE TABLE "+partial+" ("+String.join(",",StockDetailInfoDataset.DEFINITION.columns()
                            .stream().map(c->c.storageName()+" "+c.storageType().name()).toList())+")");
                    jdbc.execute("INSERT INTO "+partial+" SELECT * FROM "+table);
                    assertEquals(1,new StockDetailInfoStorage(jdbc,partial).snapshot().rows().size());
                    assertEquals(2,prepared.rows().size());
                    for(String entry:List.of(run+"-snapshot",run+"-attempt",run))
                        ledger.transition(entry,ledger.get(entry).revision(),SyncRunState.IN_DOUBT,"{}");
                    locks.retainInDoubt(lease);
                } else {
                    var stage=new StockDetailInfoStaging(jdbc).write(prepared,evidence);
                    var publisher=new StockDetailInfoPublication(jdbc,path,state->{if(state==phase) throw new AbruptStop();});
                    assertThrows(AbruptStop.class,()->publisher.publish(lease,table,prepared,stage,()->false));
                    assertEquals(SyncRunState.RUNNING,ledger.get(run).state());
                }
                assertFalse(Files.exists(evidence.resolve("completion.json")));
                var journal=new StockDetailPublicationJournal(path);
                if(phase==State.IN_DOUBT) assertNull(journal.findSingleForRun(run));
                else assertEquals(phase,journal.requireSingleForRun(run).state());
                assertThrows(IllegalStateException.class,()->owner.finishInterrupted(run,false));
                assertThrows(IllegalStateException.class,()->owner.reconcilePublished(run,true));
                // Synchronous DDL returned before Error escaped; this test has no remaining writer.
                var result=owner.finishInterrupted(run,true);assertEquals(SyncRunState.VERIFIED,result.state());
                for(String entry:List.of(run,run+"-attempt",run+"-snapshot")) assertEquals(SyncRunState.VERIFIED,ledger.get(entry).state());
                assertNull(locks.findOwned(run,DatasetIntervalLock.Scope.allDates("stock_detail_info")));
                var after=new StockDetailInfoStorage(jdbc,table).snapshot();assertEquals(prepared.rows(),after.rows());
                if(partial!=null) assertEquals(before.rows(),new StockDetailInfoStorage(jdbc,partial).snapshot().rows());
                cases.add(Map.of("interruptedPhase",phase,"result",result,"actual",after,"sourceReplayRequests",0));
                jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+journal.requireSingleForRun(run).intent().backup());
                if(partial!=null) jdbc.execute("DROP TABLE "+partial);
            }
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("finish-readback.json").toFile(),
                    Map.of("source",source,"cases",cases,"injection","Error after durable phase; not OS process termination"));
        }
    }
    private static void running(SyncRunLedger ledger,String id) throws Exception {
        ledger.transition(id,ledger.get(id).revision(),SyncRunState.RUNNING,"{}");
    }
}
