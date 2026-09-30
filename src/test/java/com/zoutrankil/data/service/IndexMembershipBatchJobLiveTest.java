package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class IndexMembershipBatchJobLiveTest {
    static class AbruptStop extends Error {}
    @Test void stoppedCoordinatorResumesWithoutReplayingVerifiedIndustry() throws Exception {
        realIndustriesRunSeriallyAndCompletedBatchReusesOnlyAfterScopeReadback("interrupt");
    }
    @ParameterizedTest @ValueSource(strings={"normal","failure","cancel"})
    void realIndustriesRunSeriallyAndCompletedBatchReusesOnlyAfterScopeReadback(String scenario) throws Exception {
        boolean failSecond=scenario.equals("failure"),cancelAfterFirst=scenario.equals("cancel"),interrupt=scenario.equals("interrupt");
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            String nonce=UUID.randomUUID().toString().replace("-","");Path folder=Path.of("artifacts/java-migration/D005","batch-"+nonce);
            Files.createDirectories(folder);Path path=folder.resolve("ledger.sqlite");String table="java_d005_batch_"+nonce;
            jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",IndexMembershipDataset.DEFINITION.columns().stream()
                    .map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList())+") TIMESTAMP(update_time) PARTITION BY YEAR WAL");
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(!QuestDbWriteChecks.walSettled(jdbc,table)) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Fixture WAL did not settle");Thread.sleep(50);
            }
            var catalog=json.treeToValue(json.readTree(Path.of("artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/discovery-readback.json").toFile())
                    .path("catalog"),IndexMembershipClassificationSource.Catalog.class);
            var codes=!scenario.equals("normal")?List.of("801011.SI","801217.SI","801768.SI"):List.of("801011.SI","801217.SI");
            var request=IndexMembershipJobPlan.freeze(catalog,codes,IndexMembershipSource.Selection.BOTH,LocalDate.of(2026,9,29));
            var calls=new ArrayList<String>();var delegate=context.getBean(TusharePageService.class);
            var inject=new java.util.concurrent.atomic.AtomicBoolean(failSecond);
            var pages=new TusharePageService(null) {
                @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> params,
                        PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                    calls.add(params.get("l2_code")+":"+params.get("is_new"));
                    if(params.get("l2_code").equals("801217.SI") && inject.compareAndSet(true,false))
                        throw new java.io.IOException("Injected second industry source failure");
                    return delegate.execute(contract,params,consumer,validator,cancelled);
                }
            };
            var cancelOnce=new java.util.concurrent.atomic.AtomicBoolean(cancelAfterFirst);
            var interruptOnce=new java.util.concurrent.atomic.AtomicBoolean(interrupt);
            var recoveryInterruptOnce=new java.util.concurrent.atomic.AtomicBoolean(interrupt);
            var interruptedRun=new java.util.concurrent.atomic.AtomicReference<String>();
            var batch=new IndexMembershipBatchJob(jdbc,pages,path,table,new IndexMembershipBatchJob.Hook() {
                public void afterMember(String run,int ordinal) throws Exception {
                    if(ordinal==0 && cancelOnce.compareAndSet(true,false)) assertTrue(new SyncRunLedger(path).requestCancellation(run));
                    if(ordinal==0 && interruptOnce.compareAndSet(true,false)) { interruptedRun.set(run);throw new AbruptStop(); }
                }
                public void afterStoppedAttempt(String run) { if(recoveryInterruptOnce.compareAndSet(true,false)) throw new AbruptStop(); }
            });
            IndexMembershipBatchJob.Result first;
            String failedRun="";
            if(interrupt) {
                assertThrows(AbruptStop.class,()->batch.run(request));failedRun=interruptedRun.get();
                assertEquals(SyncRunState.RUNNING,SyncRunLedger.openReadOnly(path).get(failedRun).state());
                assertEquals(List.of("801011.SI:Y","801011.SI:N"),calls);
                assertThrows(IllegalStateException.class,()->batch.resumeStopped(request,interruptedRun.get(),false));
                assertThrows(AbruptStop.class,()->batch.resumeStopped(request,interruptedRun.get(),true));
                assertEquals(SyncRunState.PARTIAL,SyncRunLedger.openReadOnly(path).get(failedRun+"-attempt").state());
                assertEquals(SyncRunState.RUNNING,SyncRunLedger.openReadOnly(path).get(failedRun).state());
                assertEquals(2,calls.size());
                first=batch.resumeStopped(request,failedRun,true);
                assertEquals(SyncRunState.PARTIAL,SyncRunLedger.openReadOnly(path).get(failedRun).state());
                assertTrue(first.members().getFirst().reused());
            } else first=batch.run(request);
            if(failSecond || cancelAfterFirst) {
                assertEquals(cancelAfterFirst?SyncRunState.CANCELLED:SyncRunState.PARTIAL,first.state(),first.toString());assertEquals(1,first.members().size());
                assertEquals(cancelAfterFirst?List.of("801011.SI:Y","801011.SI:N"):List.of("801011.SI:Y","801011.SI:N","801217.SI:Y"),calls);
                var failedLedger=SyncRunLedger.openReadOnly(path);failedRun=first.runId();
                assertEquals(SyncRunState.PENDING,failedLedger.get(failedRun+"-industry-2").state());
                if(failSecond) assertEquals(SyncRunState.FAILED,failedLedger.get(failedRun+"-child-1").state());
                else assertEquals(SyncRunState.PENDING,failedLedger.get(failedRun+"-industry-1").state());
                first=batch.resume(request,failedRun);
                assertTrue(first.members().getFirst().reused());
                assertFalse(first.members().get(1).reused());assertFalse(first.members().get(2).reused());
            }
            assertEquals(SyncRunState.VERIFIED,first.state(),first.toString());assertEquals(codes.size(),first.members().size());
            var expectedCalls=failSecond?List.of("801011.SI:Y","801011.SI:N","801217.SI:Y","801217.SI:Y","801217.SI:N","801768.SI:Y","801768.SI:N")
                    :cancelAfterFirst || interrupt?List.of("801011.SI:Y","801011.SI:N","801217.SI:Y","801217.SI:N","801768.SI:Y","801768.SI:N")
                    :List.of("801011.SI:Y","801011.SI:N","801217.SI:Y","801217.SI:N");
            assertEquals(expectedCalls,calls);
            var before=new IndexMembershipStorage(jdbc,table).snapshot();assertEquals(7,before.rows().size());
            var resumed=batch.resume(request,first.runId());assertEquals(SyncRunState.VERIFIED,resumed.state(),resumed.toString());
            assertTrue(resumed.members().stream().allMatch(IndexMembershipBatchJob.Member::reused));assertEquals(expectedCalls.size(),calls.size());
            assertEquals(before,new IndexMembershipStorage(jdbc,table).snapshot());
            var ledger=SyncRunLedger.openReadOnly(path);
            for(var result:List.of(first,resumed)) {
                assertEquals(codes.size()+2,ledger.entries(result.runId(),null,10).size());
                assertEquals(SyncRunState.VERIFIED,ledger.get(result.runId()).state());
                var locks=new DatasetIntervalLock(path);
                assertNull(locks.findOwned(result.runId(),DatasetIntervalLock.Scope.allDates("index_member_batch")));
                assertNull(locks.findOwned(result.runId(),DatasetIntervalLock.Scope.allDates("index_member")));
            }
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("batch-readback.json").toFile(),Map.of(
                    "first",first,"resumed",resumed,"requestOrder",calls,"actual",before,"resumeSourceRequests",0,"failedRun",failedRun,"scenario",scenario));
            var publication=new ReferencePublicationJournal(path,"index_member").forRun(first.members().getFirst().childRunId());
            jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+publication.intent().backup());
        }
    }
}
