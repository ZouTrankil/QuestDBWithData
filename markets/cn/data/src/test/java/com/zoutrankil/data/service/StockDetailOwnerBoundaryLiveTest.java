package com.zoutrankil.data.service;
import com.zoutrankil.data.stock.storage.QuestDbStockDetailTarget;

import com.zoutrankil.data.stock.application.StockDetailInfoJobService;
import com.zoutrankil.data.stock.storage.StockDetailInfoStorage;

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

/** Synthetic provider outcomes, real QuestDB preservation and durable control-state checks. */
@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class StockDetailOwnerBoundaryLiveTest {
    private static TusharePageService source(PageExecutor.Fetcher fetcher) {
        return new TusharePageService(null) {
            @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> params,
                    PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                return new PageExecutor().execute(contract,params,fetcher,consumer,validator,cancelled);
            }
        };
    }

    @Test void failuresEmptyCancellationAndBusyNeverPublishOrDeleteExistingRows() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f)
                .removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");
            String target="java_d002_boundary_"+id;
            Path path=Path.of("var","D002-boundary-"+id+".sqlite");
            Path folder=Path.of("artifacts/java-migration/D002","boundary-"+id);
            var jdbc=ctx.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE "+target+" ("+String.join(",",StockDetailInfoDataset.DEFINITION.columns()
                    .stream().map(c->c.storageName()+" "+c.storageType().name()).toList())+")");
            boolean verified=false;
            try {
                jdbc.execute("INSERT INTO "+target+" (ts_code,update_time,name,list_status,list_date,delist_date) "
                        +"VALUES ('000001.SZ',cast(0 AS TIMESTAMP),'preserved fixture','L','19910403','None')");
                var storage=new StockDetailInfoStorage(jdbc,target);var before=storage.snapshot();
                var calls=new AtomicInteger();
                var empty=source(params->{calls.incrementAndGet();return new PageExecutor.Page(List.of(),null,false,null);});
                var owner=new StockDetailInfoJobService(empty,new QuestDbStockDetailTarget(jdbc,target),path);
                var request=owner.plan(List.of("000001.SZ"),false,LocalDate.of(2026,9,29));
                var emptyResult=owner.run(request);
                assertEquals(SyncRunState.VERIFIED_EMPTY,emptyResult.state(),emptyResult.errorCode());
                assertEquals(3,calls.get());assertNull(emptyResult.publicationId());
                var failureOwner=new StockDetailInfoJobService(source(params->{throw new java.io.IOException("provider fixture failure");}),new QuestDbStockDetailTarget(jdbc,target),path);
                var failed=failureOwner.run(request);
                assertEquals(SyncRunState.FAILED,failed.state());assertNull(failed.publicationId());
                var ledger=new SyncRunLedger(path);
                var cancelledSource=source(params->{
                    ledger.requestCancellation("cancel-"+id);
                    return new PageExecutor.Page(List.of(),null,false,null);
                });
                var cancelled=new StockDetailInfoJobService(cancelledSource,new QuestDbStockDetailTarget(jdbc,target),path)
                        .execute("cancel-"+id,null,request);
                assertEquals(SyncRunState.CANCELLED,cancelled.state());assertNull(cancelled.publicationId());
                var locks=new DatasetIntervalLock(path);var scope=DatasetIntervalLock.Scope.allDates("stock_detail_info");
                for(var result:List.of(emptyResult,failed,cancelled)) {
                    assertEquals(result.state(),ledger.get(result.runId()).state());
                    assertEquals(result.state(),ledger.get(result.runId()+"-attempt").state());
                    assertEquals(result.state(),ledger.get(result.runId()+"-snapshot").state());
                    assertNull(locks.findOwned(result.runId(),scope));
                }
                String blocker="blocker-"+id;ledger.createRun(blocker,null,owner.targetId(),request);
                var lease=locks.acquire(blocker,scope);assertNotNull(lease);
                int callsBeforeBusy=calls.get();var busy=owner.run(request);
                assertEquals(SyncRunState.FAILED,busy.state());assertEquals("DATASET_INTERVAL_BUSY",busy.errorCode());
                assertEquals(callsBeforeBusy,calls.get());assertNotNull(locks.findOwned(blocker,scope));
                locks.releaseVerified(lease);
                var after=storage.snapshot();assertEquals(before.identity(),after.identity());
                assertEquals(before.rows(),after.rows());assertEquals(before.fingerprint(),after.fingerprint());
                Files.createDirectories(folder);
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(
                        folder.resolve("boundary-readback.json").toFile(),Map.of("sourceKind","synthetic provider outcomes",
                                "target",target,"ledger",path.toString(),"before",before,"after",after,
                                "empty",emptyResult,"failed",failed,"cancelled",cancelled,"busy",busy));
                verified=true;
            } finally { if(verified) jdbc.execute("DROP TABLE "+target); }
        }
    }
}
