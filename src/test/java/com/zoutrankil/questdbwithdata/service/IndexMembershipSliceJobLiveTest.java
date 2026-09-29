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
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class IndexMembershipSliceJobLiveTest {
    @Test void realSingleIndustryRunAndIdempotentRepeatVerifyAllLedgerLevelsAndRows() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            String nonce=UUID.randomUUID().toString().replace("-","");Path folder=Path.of("artifacts/java-migration/D005","slice-owner-"+nonce);
            Files.createDirectories(folder);Path ledgerPath=folder.resolve("ledger.sqlite");
            var catalog=json.treeToValue(json.readTree(Path.of("artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/discovery-readback.json").toFile())
                    .path("catalog"),IndexMembershipClassificationSource.Catalog.class);
            var request=IndexMembershipJobPlan.freeze(catalog,List.of("801011.SI"),IndexMembershipSource.Selection.BOTH,LocalDate.of(2026,9,29));
            String table="java_d005_owner_"+nonce;
            jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",IndexMembershipDataset.DEFINITION.columns().stream()
                    .map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList())+") TIMESTAMP(update_time) PARTITION BY YEAR WAL");
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(!QuestDbWriteChecks.walSettled(jdbc,table)) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Owner fixture WAL did not settle");Thread.sleep(50);
            }
            var productionBefore=new IndexMembershipStorage(jdbc,"index_member").snapshot();
            var owner=new IndexMembershipSliceJob(jdbc,context.getBean(TusharePageService.class),ledgerPath,table);
            var first=owner.run(request);assertEquals(SyncRunState.VERIFIED,first.state(),first.toString());
            assertEquals(7,first.sourceRows());assertEquals(7,first.inserted());assertEquals(7,first.submittedStageRows());
            var firstSnapshot=new IndexMembershipStorage(jdbc,table).snapshot();
            var second=owner.run(request);assertEquals(SyncRunState.VERIFIED,second.state(),second.toString());
            assertEquals(7,second.unchanged());assertEquals(0,second.inserted());assertEquals(0,second.revised());assertEquals(0,second.submittedStageRows());
            assertEquals(firstSnapshot,new IndexMembershipStorage(jdbc,table).snapshot());
            var emptyRequest=IndexMembershipJobPlan.freeze(catalog,List.of("801217.SI"),IndexMembershipSource.Selection.BOTH,request.logicalDate());
            var empty=owner.run(emptyRequest);assertEquals(SyncRunState.VERIFIED_EMPTY,empty.state(),empty.toString());
            assertEquals(0,empty.sourceRows());assertEquals(0,empty.submittedStageRows());
            assertEquals(firstSnapshot,new IndexMembershipStorage(jdbc,table).snapshot());
            var ledger=SyncRunLedger.openReadOnly(ledgerPath);var locks=new DatasetIntervalLock(ledgerPath);
            for(var run:List.of(first,second,empty)) {
                var entries=ledger.entries(run.runId(),null,10);assertEquals(3,entries.size());
                assertTrue(entries.stream().allMatch(e->e.state()==run.state()));
                assertNull(locks.findOwned(run.runId(),DatasetIntervalLock.Scope.allDates("index_member")));
                var proof=json.readTree(Path.of(run.evidence()).toFile());
                var actual=json.treeToValue(proof.path("actual"),IndexMembershipStorage.Snapshot.class);
                assertEquals(firstSnapshot,actual);
                List<IndexMembership> source=json.convertValue(proof.path("source").path("rows"),new com.fasterxml.jackson.core.type.TypeReference<>() {});
                var byKey=new HashMap<IndexMembership.Key,IndexMembership>();actual.businessRows().forEach(r->byKey.put(r.key(),r));
                for(var row:source) assertTrue(IndexMembershipMerge.sameBusinessValues(row,byKey.get(row.key())));
            }
            assertEquals(productionBefore,new IndexMembershipStorage(jdbc,"index_member").snapshot());
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("owner-readback.json").toFile(),Map.of(
                    "first",first,"second",second,"empty",empty,"actual",firstSnapshot,"threeLedgerLevelsVerified",true,"productionUnchanged",true));
            var publication=new ReferencePublicationJournal(ledgerPath,"index_member").forRun(first.runId());
            jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+publication.intent().backup());
        }
    }
}
