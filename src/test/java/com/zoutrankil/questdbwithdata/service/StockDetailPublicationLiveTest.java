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
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class StockDetailPublicationLiveTest {
    @Test void realSourcePublishesOwnedTargetAndReopensVerifiedJournal() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");String target="java_d002_publish_"+id;
            var folder=Path.of("artifacts/java-migration/D002","publication-"+id);var ledgerPath=Path.of("var","D002-publish-"+id+".sqlite");
            var jdbc=ctx.getBean(JdbcTemplate.class);var columns=StockDetailInfoDataset.DEFINITION.columns();
            jdbc.execute("CREATE TABLE "+target+" ("+String.join(",",columns.stream().map(c->c.storageName()+" "+c.storageType().name()).toList())+")");
            var ledger=new SyncRunLedger(ledgerPath);
            ledger.createRun(new SyncRunLedger.Run("publish-test",null,"test.stock_detail_publication",1,"2026-09-29","isolated","{}"));
            var locks=new DatasetIntervalLock(ledgerPath);var lease=locks.acquire("publish-test",DatasetIntervalLock.Scope.allDates("stock_detail_info"));
            assertNotNull(lease);boolean verified=false;String backup=null;String retainedStage=null;
            try {
                var before=new StockDetailInfoStorage(jdbc,target).snapshot();
                var source=new StockDetailInfoSource(ctx.getBean(TusharePageService.class),folder);
                var page=source.fetch("000001.SZ",Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
                assertEquals(1,page.rows().size());var prepared=StockDetailInfoStaging.prepare(before,page.rows());
                var staged=new StockDetailInfoStaging(jdbc).write(prepared,folder);
                var publisher=new StockDetailInfoPublication(jdbc,ledgerPath);
                var result=publisher.publish(lease,target,prepared,staged,()->false);backup=result.entry().intent().backup();
                assertEquals(StockDetailPublicationJournal.State.VERIFIED,result.entry().state());
                assertEquals(StockDetailInfoPublication.Layout.PUBLISHED,result.inspection().layout());
                var reopened=new StockDetailInfoPublication(jdbc,ledgerPath);
                assertEquals(StockDetailInfoPublication.Layout.PUBLISHED,reopened.inspect(result.entry().intent().id()).layout());
                var actual=new StockDetailInfoStorage(jdbc,target).snapshot();assertEquals(page.rows(),actual.businessRows());
                assertEquals(before.rows(),new StockDetailInfoStorage(jdbc,backup).snapshot().rows());
                assertEquals(StockDetailPublicationJournal.State.VERIFIED,new StockDetailPublicationJournal(ledgerPath).get(result.entry().intent().id()).state());
                locks.releaseVerified(lease);
                ledger.createRun(new SyncRunLedger.Run("fault-test",null,"test.stock_detail_publication",1,"2026-09-29","isolated","{}"));
                var faultLease=locks.acquire("fault-test",DatasetIntervalLock.Scope.allDates("stock_detail_info"));assertNotNull(faultLease);
                var extra=source.fetch("000003.SZ",Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
                assertEquals(1,extra.rows().size());
                var next=StockDetailInfoStaging.prepare(actual,extra.rows());
                var nextStage=new StockDetailInfoStaging(jdbc).write(next,folder);retainedStage=nextStage.table();
                var broken=new StockDetailInfoPublication(jdbc,ledgerPath,state->{
                    if(state==StockDetailPublicationJournal.State.OLD_RENAMED) throw new IllegalStateException("Injected synchronous phase failure");
                });
                var failure=assertThrows(StockDetailInfoPublication.Uncertain.class,
                        ()->broken.publish(faultLease,target,next,nextStage,()->false));
                var recovery=new StockDetailInfoPublication(jdbc,ledgerPath);
                var interrupted=recovery.inspect(failure.publicationId());
                assertEquals(StockDetailInfoPublication.Layout.OLD_MOVED,interrupted.layout());
                assertThrows(IllegalStateException.class,()->recovery.restoreOriginal(faultLease,failure.publicationId(),false));
                // The injected hook ran after the synchronous rename returned; this test owns and has stopped that writer.
                var restored=recovery.restoreOriginal(faultLease,failure.publicationId(),true);
                assertEquals(StockDetailPublicationJournal.State.ROLLED_BACK,restored.entry().state());
                assertEquals(actual.rows(),new StockDetailInfoStorage(jdbc,target).snapshot().rows());
                locks.releaseAfterReconciliation(faultLease,true,true);
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("publication-readback.json").toFile(),
                        Map.of("result",result,"actual",actual,"source",page,"ledger",ledgerPath.toString(),"backupVerified",true,
                                "interrupted",interrupted,"restored",restored));
                verified=true;
            } finally { if(verified) { jdbc.execute("DROP TABLE "+target);jdbc.execute("DROP TABLE "+backup);jdbc.execute("DROP TABLE "+retainedStage); } }
        }
    }
}
