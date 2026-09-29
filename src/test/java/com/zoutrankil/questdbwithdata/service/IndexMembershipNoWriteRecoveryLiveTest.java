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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class IndexMembershipNoWriteRecoveryLiveTest {
    static class AbruptStop extends Error {}
    @Test void unchangedAndEmptyObservationsRecoverFromActualUnchangedTable() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            String nonce=UUID.randomUUID().toString().replace("-","");Path folder=Path.of("artifacts/java-migration/D005","no-write-recovery-"+nonce);
            Files.createDirectories(folder);Path path=folder.resolve("ledger.sqlite");String emptyTable="java_d005_no_write_"+nonce;
            jdbc.execute("CREATE TABLE "+emptyTable+" ("+String.join(",",IndexMembershipDataset.DEFINITION.columns().stream()
                    .map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList())+") TIMESTAMP(update_time) PARTITION BY YEAR WAL");
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(!QuestDbWriteChecks.walSettled(jdbc,emptyTable)) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Fixture WAL did not settle");Thread.sleep(50);
            }
            List<IndexMembership> seed=json.convertValue(json.readTree(Path.of("artifacts/java-migration/D005/source-adapter-7ec60463-be52-45b5-bcf2-bebdee34fea3/source-review.json").toFile())
                    .path("result").path("rows"),new com.fasterxml.jackson.core.type.TypeReference<>() {});
            var seeded=new IndexMembershipStaging(jdbc).write(IndexMembershipStaging.prepare(new IndexMembershipStorage(jdbc,emptyTable).snapshot(),seed,"801011.SI"),folder.resolve("seed"),()->false);
            String table=seeded.table();var before=new IndexMembershipStorage(jdbc,table).snapshot();
            var walBefore=jdbc.queryForList("SELECT writerTxn,sequencerTxn FROM wal_tables() WHERE name=?",table);
            var catalog=json.treeToValue(json.readTree(Path.of("artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/discovery-readback.json").toFile())
                    .path("catalog"),IndexMembershipClassificationSource.Catalog.class);
            var calls=new AtomicInteger();var delegate=context.getBean(TusharePageService.class);
            var pages=new TusharePageService(null) {
                @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> params,
                        PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                    calls.incrementAndGet();return delegate.execute(contract,params,consumer,validator,cancelled);
                }
            };
            var owner=new IndexMembershipSliceJob(jdbc,pages,path,table,new IndexMembershipSliceJob.Hook() {
                public void afterPublication(String run) {fail("No publication expected");}
                public void afterPrepared(String run) {throw new AbruptStop();}
            });
            var cases=new ArrayList<Object>();
            for(String code:List.of("801011.SI","801217.SI")) {
                String run="no-write-"+code+"-"+nonce;
                var request=IndexMembershipJobPlan.freeze(catalog,List.of(code),IndexMembershipSource.Selection.BOTH,LocalDate.of(2026,9,29));
                assertThrows(AbruptStop.class,()->owner.execute(run,null,request));int requests=calls.get();
                var ledger=SyncRunLedger.openReadOnly(path);assertEquals(SyncRunState.RUNNING,ledger.get(run).state());
                assertThrows(IllegalStateException.class,()->owner.finishInterrupted(run,false));
                var result=owner.finishInterrupted(run,true);
                var expected=code.equals("801011.SI")?SyncRunState.VERIFIED:SyncRunState.VERIFIED_EMPTY;
                assertEquals(expected,result.state());assertEquals(code.equals("801011.SI")?7:0,result.sourceRows());
                assertEquals(0,result.submittedStageRows());assertEquals(requests,calls.get());
                assertEquals(before,new IndexMembershipStorage(jdbc,table).snapshot());
                assertEquals(walBefore,jdbc.queryForList("SELECT writerTxn,sequencerTxn FROM wal_tables() WHERE name=?",table));
                assertEquals(3,ledger.entries(run,null,10).size());assertTrue(ledger.entries(run,null,10).stream().allMatch(e->e.state()==expected));
                assertNull(new DatasetIntervalLock(path).findOwned(run,DatasetIntervalLock.Scope.allDates("index_member")));
                assertTrue(new ReferencePublicationJournal(path,"index_member").findForRun(run).isEmpty());
                cases.add(Map.of("industry",code,"result",result,"recoverySourceRequests",0,"recoveryWalTransactions",0,"actual",before));
            }
            var unavailable=new TusharePageService(null) {
                @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> params,
                        PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                    throw new java.io.IOException("Injected source failure before any membership write");
                }
            };
            var retryRequest=IndexMembershipJobPlan.freeze(catalog,List.of("801011.SI"),IndexMembershipSource.Selection.BOTH,LocalDate.of(2026,9,29));
            var failed=new IndexMembershipSliceJob(jdbc,unavailable,path,table).run(retryRequest);
            assertEquals(SyncRunState.FAILED,failed.state());assertEquals(before,new IndexMembershipStorage(jdbc,table).snapshot());
            assertNull(new DatasetIntervalLock(path).findOwned(failed.runId(),DatasetIntervalLock.Scope.allDates("index_member")));
            var retryOwner=new IndexMembershipSliceJob(jdbc,pages,path,table);
            var changedRequest=IndexMembershipJobPlan.freeze(catalog,List.of("801217.SI"),IndexMembershipSource.Selection.BOTH,retryRequest.logicalDate());
            int beforeRetry=calls.get();
            assertThrows(IllegalStateException.class,()->retryOwner.resume(changedRequest,failed.runId()));assertEquals(beforeRetry,calls.get());
            var retried=retryOwner.resume(retryRequest,failed.runId());assertEquals(SyncRunState.VERIFIED,retried.state());
            assertEquals(7,retried.unchanged());assertEquals(0,retried.submittedStageRows());assertEquals(beforeRetry+2,calls.get());
            assertEquals(before,new IndexMembershipStorage(jdbc,table).snapshot());
            assertEquals(walBefore,jdbc.queryForList("SELECT writerTxn,sequencerTxn FROM wal_tables() WHERE name=?",table));
            assertThrows(IllegalStateException.class,()->retryOwner.resume(retryRequest,retried.runId()));
            cases.add(Map.of("failedBeforeWrite",failed,"retry",retried,"sameTarget",true,"changedRequestRejected",true));
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("recovery-readback.json").toFile(),Map.of("cases",cases,
                    "injection","Error after frozen preparation, before completion; no OS kill"));
            jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+emptyTable);
        }
    }
}
