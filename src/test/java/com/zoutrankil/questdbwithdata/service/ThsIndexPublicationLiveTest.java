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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.questdbwithdata.repository.ReferencePublicationJournal.State;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class ThsIndexPublicationLiveTest {
    static class AbruptStop extends Error {}
    @Test void sourceToIsolatedTargetAndInterruptedRenameAreFullyReconciled() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");
            Path folder=Path.of("artifacts/java-migration/D004","publication-"+id),path=Path.of("var","D004-publication-"+id+".sqlite");
            var jdbc=context.getBean(JdbcTemplate.class);var ledger=new SyncRunLedger(path);var locks=new DatasetIntervalLock(path);
            var source=new ThsIndexSource(context.getBean(TusharePageService.class),folder)
                    .fetch(ThsIndexSource.Scope.all(),Instant.now().truncatedTo(ChronoUnit.MICROS),()->false);
            assertTrue(source.rows().size()>0 && source.rows().size()<5000);
            var cases=new ArrayList<Object>();
            for(boolean interrupt:List.of(false,true)) {
                String table="java_d004_publish_"+interrupt+"_"+id,run="ths-publish-"+interrupt+"-"+id;
                jdbc.execute("CREATE TABLE \""+table+"\" (ts_code SYMBOL,name STRING,\"count\" INT,exchange STRING,"
                        +"list_date STRING,\"type\" STRING,update_time TIMESTAMP) TIMESTAMP(update_time) "
                        +"PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)");
                var before=new ThsIndexStorage(jdbc,table).snapshot();assertTrue(before.rows().isEmpty());
                var target=StaticTargetIdentity.identify(jdbc,table,before.identity().id(),before.identity().directory());
                ledger.createRun(new SyncRunLedger.Run(run,null,"test.ths_publication",1,"2026-09-29",target,"{}"));
                var lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("ths_index"));assertNotNull(lease);
                var prepared=ThsIndexStaging.prepare(before,source.rows(),ThsIndexSource.Scope.all());
                var stage=new ThsIndexStaging(jdbc).write(prepared,folder,()->false);
                var publisher=new ThsIndexPublication(jdbc,path,state->{if(interrupt && state==State.OLD_MOVED) throw new AbruptStop();});
                ThsIndexPublication.Result result;
                if(interrupt) {
                    assertThrows(AbruptStop.class,()->publisher.publish(lease,table,prepared,stage,()->false));
                    var recovery=new ThsIndexPublication(jdbc,path);
                    assertEquals(ThsIndexPublication.Layout.OLD_MOVED,recovery.inspect(run));
                    assertThrows(IllegalStateException.class,()->recovery.finish(lease,false));
                    result=recovery.finish(lease,true);
                } else result=publisher.publish(lease,table,prepared,stage,()->false);
                assertEquals(State.VERIFIED,result.publication().state());
                assertEquals(prepared.rows(),result.actual().rows());
                assertEquals(source.rows().size(),result.actual().rows().size());
                assertTrue(QuestDbWriteChecks.walSettled(jdbc,table));
                assertEquals(before.rows(),new ThsIndexStorage(jdbc,result.publication().intent().backup()).snapshot().rows());
                var repeated=ThsIndexStaging.prepare(result.actual(),source.rows(),ThsIndexSource.Scope.all());
                assertFalse(repeated.merge().requiresWrite());
                cases.add(Map.of("interrupted",interrupt,"run",run,"result",result,"sourceFingerprint",source.sourceFingerprint(),
                        "sameSourceRerunRequiresWrite",false,"physicalRows",result.actual().rows().size()));
                var actualLease=locks.findOwned(run,lease.scope());
                if(actualLease.inDoubt()) locks.releaseAfterReconciliation(actualLease,true,true);else locks.releaseVerified(actualLease);
                jdbc.execute("DROP TABLE \""+table+"\"");jdbc.execute("DROP TABLE \""+result.publication().intent().backup()+"\"");
            }
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("publication-readback.json").toFile(),
                    Map.of("sourceReceipt",source.responseEvidence(),"sourceRows",source.rows().size(),"cases",cases,
                            "productionWrites",0,"injection","Error after old-name rename; stopped-writer recovery required"));
        }
    }
}
