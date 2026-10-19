package com.zoutrankil.data.service;
import com.zoutrankil.data.stock.storage.QuestDbStockDetailTarget;

import com.zoutrankil.data.stock.application.StockDetailInfoJobService;
import com.zoutrankil.data.stock.storage.StockDetailInfoStorage;
import com.zoutrankil.data.stock.storage.StockDetailPublicationJournal;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
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
class StockDetailInfoOwnerLiveTest {
    @Test void ownerRepeatsWithoutPublicationThenIncrementallyAddsAnotherCode() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");String target="java_d002_owner_"+id;
            var path=Path.of("var","D002-owner-"+id+".sqlite");var folder=Path.of("artifacts/java-migration/D002","owner-"+id);
            var jdbc=ctx.getBean(JdbcTemplate.class);var columns=StockDetailInfoDataset.DEFINITION.columns();
            jdbc.execute("CREATE TABLE "+target+" ("+String.join(",",columns.stream().map(c->c.storageName()+" "+c.storageType().name()).toList())+")");
            var backups=new ArrayList<String>();boolean verified=false;
            try {
                var pages=ctx.getBean(TusharePageService.class);var owner=new StockDetailInfoJobService(pages,new QuestDbStockDetailTarget(jdbc,target),path);
                var day=LocalDate.of(2026,9,29);var firstPlan=owner.plan(List.of("000001.SZ"),false,day);
                var first=owner.run(firstPlan);assertEquals(SyncRunState.VERIFIED,first.state(),first.errorCode());
                assertEquals(1,first.insertedRows());assertNull(first.errorCode());
                var journal=new StockDetailPublicationJournal(path);backups.add(journal.get(first.publicationId()).intent().backup());
                var original=new StockDetailInfoStorage(jdbc,target).snapshot();
                var reopened=new StockDetailInfoJobService(pages,new QuestDbStockDetailTarget(jdbc,target),path);
                var repeated=reopened.run(firstPlan);assertEquals(SyncRunState.VERIFIED,repeated.state(),repeated.errorCode());
                assertEquals(1,repeated.unchangedRows());assertNull(repeated.publicationId());
                assertEquals(original.identity(),new StockDetailInfoStorage(jdbc,target).snapshot().identity());
                var firstTarget=SyncRunLedger.openReadOnly(path).getRun(first.runId()).targetId();
                assertTrue(firstTarget.startsWith("static-v2-"));
                assertEquals(first.evidence(),reopened.revalidateGroupChild(first.runId(),firstTarget,firstPlan));
                var added=reopened.run(reopened.plan(List.of("000003.SZ"),false,day));
                assertEquals(SyncRunState.VERIFIED,added.state(),added.errorCode());assertEquals(1,added.insertedRows());
                backups.add(journal.get(added.publicationId()).intent().backup());
                var actual=new StockDetailInfoStorage(jdbc,target).snapshot();assertEquals(2,actual.rows().size());
                assertThrows(IllegalStateException.class,()->reopened.revalidateGroupChild(first.runId(),firstTarget,firstPlan));
                assertEquals(original.rows().getFirst(),actual.rows().getFirst());
                var ledger=SyncRunLedger.openReadOnly(path);
                for(var result:List.of(first,repeated,added)) {
                    assertEquals(SyncRunState.VERIFIED,ledger.get(result.runId()).state());
                    assertEquals(SyncRunState.VERIFIED,ledger.get(result.runId()+"-attempt").state());
                    assertTrue(Files.isRegularFile(Path.of(result.evidence())));
                }
                Files.createDirectories(folder);
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("owner-readback.json").toFile(),
                        Map.of("first",first,"repeated",repeated,"incremental",added,"actual",actual,"ledger",path.toString()));
                verified=true;
            } finally { if(verified) { jdbc.execute("DROP TABLE "+target);for(String table:backups) jdbc.execute("DROP TABLE "+table); } }
        }
    }
}
