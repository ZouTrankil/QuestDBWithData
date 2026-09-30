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
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.repository.ReferencePublicationJournal.State;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class IndexCatalogPublicationLiveTest {
    static class AbruptStop extends Error {}
    @Test void walPublicationAndInterruptedRenameRecoveryPreserveExactRows() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");var folder=Path.of("artifacts/java-migration/D003","publication-"+id);
            var path=Path.of("var","D003-publication-"+id+".sqlite");var ledger=new SyncRunLedger(path);var locks=new DatasetIntervalLock(path);
            var jdbc=ctx.getBean(JdbcTemplate.class);var source=new IndexCatalogFileSource().read(Path.of("artifacts/java-migration/D003/source-catalog.csv"),
                    Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
            var cases=new ArrayList<Object>();
            for(boolean interrupt:List.of(false,true)) {
                String table="java_d003_publish_"+interrupt+"_"+id;String run="catalog-publish-"+interrupt+"-"+id;
                jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",IndexCatalogDataset.DEFINITION.columns().stream()
                        .map(c->c.storageName()+" "+c.storageType().name()).toList())+") timestamp(import_time) PARTITION BY MONTH WAL");
                var before=new IndexCatalogStorage(jdbc,table).snapshot();
                String target=StaticTargetIdentity.identify(jdbc,table,before.identity().id(),before.identity().directory());
                ledger.createRun(new SyncRunLedger.Run(run,null,"test.index_publication",1,"2026-09-29",target,"{}"));
                var lease=locks.acquire(run,DatasetIntervalLock.Scope.allDates("index"));assertNotNull(lease);
                var prepared=IndexCatalogStaging.prepare(before,source.rows().subList(0,2));
                var stage=new IndexCatalogStaging(jdbc).write(prepared,folder,()->false);
                var publisher=new IndexCatalogPublication(jdbc,path,state->{if(interrupt && state==State.OLD_MOVED) throw new AbruptStop();});
                IndexCatalogPublication.Result result;
                if(interrupt) {
                    assertThrows(AbruptStop.class,()->publisher.publish(lease,table,prepared,stage,()->false));
                    var recovery=new IndexCatalogPublication(jdbc,path);
                    assertEquals(IndexCatalogPublication.Layout.OLD_MOVED,recovery.inspect(run));
                    assertThrows(IllegalStateException.class,()->recovery.finish(lease,false));
                    result=recovery.finish(lease,true);
                } else result=publisher.publish(lease,table,prepared,stage,()->false);
                assertEquals(State.VERIFIED,result.publication().state());assertEquals(prepared.rows(),result.actual().rows());
                assertTrue(QuestDbWriteChecks.walSettled(jdbc,table));
                assertEquals(before.rows(),new IndexCatalogStorage(jdbc,result.publication().intent().backup()).snapshot().rows());
                cases.add(Map.of("interrupted",interrupt,"result",result,"sourceHash",source.sha256()));
                var actualLease=locks.findOwned(run,lease.scope());
                if(actualLease.inDoubt()) locks.releaseAfterReconciliation(actualLease,true,true);else locks.releaseVerified(actualLease);
                jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+result.publication().intent().backup());
            }
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("publication-readback.json").toFile(),
                    Map.of("cases",cases,"injection","Error after synchronous old-name rename; not OS process termination"));
        }
    }
}
