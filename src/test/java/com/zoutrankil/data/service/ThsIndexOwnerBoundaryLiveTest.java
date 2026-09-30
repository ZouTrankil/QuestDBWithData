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
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Fault injection only; real database preservation does not imply real provider outcomes. */
@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class ThsIndexOwnerBoundaryLiveTest {
    private TusharePageService source(PageExecutor.Fetcher fetcher) {
        return new TusharePageService(null) {
            @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> params,
                    PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                return new PageExecutor().execute(contract,params,fetcher,consumer,validator,cancelled);
            }
        };
    }
    @Test void emptyFailureCancellationAndBusyPreserveExistingDatabaseAndLedger() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);String nonce=UUID.randomUUID().toString().replace("-","");
            Path folder=Path.of("artifacts/java-migration/D004","boundary-"+nonce),path=folder.resolve("ledger.sqlite");
            Files.createDirectories(folder);String emptyTable="java_d004_boundary_"+nonce;
            jdbc.execute("CREATE TABLE "+emptyTable+" (ts_code SYMBOL,name STRING,\"count\" INT,exchange STRING,"
                    +"list_date STRING,\"type\" STRING,update_time TIMESTAMP) TIMESTAMP(update_time) "
                    +"PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)");
            var production=new ThsIndexStorage(jdbc,"ths_index").snapshot();
            var actualRow=production.businessRows().getFirst();
            var seed=ThsIndexStaging.prepare(new ThsIndexStorage(jdbc,emptyTable).snapshot(),List.of(actualRow),
                    new ThsIndexSource.Scope(actualRow.tsCode(),null,null));
            var staged=new ThsIndexStaging(jdbc).write(seed,folder.resolve("seed"),()->false);
            String table=staged.table();var storage=new ThsIndexStorage(jdbc,table);var before=storage.snapshot();
            var calls=new AtomicInteger();
            var owner=new ThsIndexJobService(jdbc,source(p->{calls.incrementAndGet();return new PageExecutor.Page(List.of(),null,false,null);}),path,table);
            var request=owner.plan(LocalDate.of(2026,9,29));var empty=owner.run(request);
            assertEquals(SyncRunState.FAILED,empty.state());assertEquals(1,calls.get());
            var failure=new ThsIndexJobService(jdbc,source(p->{throw new java.io.IOException("controlled provider failure");}),path,table).run(request);
            assertEquals(SyncRunState.FAILED,failure.state());
            var ledger=new SyncRunLedger(path);String cancelId="cancel-"+nonce;
            var cancelled=new ThsIndexJobService(jdbc,source(p->{ledger.requestCancellation(cancelId);
                return new PageExecutor.Page(List.of(),null,false,null);}),path,table).execute(cancelId,null,request);
            assertEquals(SyncRunState.CANCELLED,cancelled.state());
            var locks=new DatasetIntervalLock(path);var scope=DatasetIntervalLock.Scope.allDates("ths_index");
            for(var result:List.of(empty,failure,cancelled)) {
                assertNull(result.publicationId());assertNull(locks.findOwned(result.runId(),scope));
                for(String id:List.of(result.runId(),result.runId()+"-attempt",result.runId()+"-snapshot"))
                    assertEquals(result.state(),ledger.get(id).state());
                assertTrue(new ReferencePublicationJournal(path,"ths_index").findForRun(result.runId()).isEmpty());
            }
            String blocker="blocker-"+nonce;ledger.createRun(blocker,null,owner.targetId(),request);
            var lease=locks.acquire(blocker,scope);assertNotNull(lease);
            int priorCalls=calls.get();var busy=owner.run(request);
            assertEquals("DATASET_INTERVAL_BUSY",busy.errorCode());assertEquals(priorCalls,calls.get());
            assertNotNull(locks.findOwned(blocker,scope));locks.releaseVerified(lease);
            var after=storage.snapshot();assertEquals(before,after);
            assertEquals(production,new ThsIndexStorage(jdbc,"ths_index").snapshot());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("boundary-readback.json").toFile(),
                    Map.of("sourceKind","controlled failures; seed is one existing production row",
                            "before",before,"after",after,"empty",empty,"failed",failure,"cancelled",cancelled,"busy",busy,
                            "productionUnchanged",true));
            jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+emptyTable);
        }
    }
}
