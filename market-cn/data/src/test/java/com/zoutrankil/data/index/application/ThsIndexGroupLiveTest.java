package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.storage.QuestDbThsIndexTarget;

import com.zoutrankil.data.index.storage.ThsIndexStorage;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.stock.application.StockBasicJobService;

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

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class ThsIndexGroupLiveTest {
    @Test void groupSyncAndResumeRetainFrozenOriginalTargetAndRevalidateCurrentRows() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);String nonce=UUID.randomUUID().toString().replace("-","");
            String table="java_d004_group_"+nonce;Path folder=Path.of("artifacts/java-migration/D004","group-"+nonce);
            Files.createDirectories(folder);Path path=folder.resolve("ledger.sqlite");
            var production=new ThsIndexStorage(jdbc,"ths_index").snapshot();
            jdbc.execute("CREATE TABLE "+table+" (ts_code SYMBOL,name STRING,\"count\" INT,exchange STRING,"
                    +"list_date STRING,\"type\" STRING,update_time TIMESTAMP) TIMESTAMP(update_time) "
                    +"PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)");
            var calls=new AtomicInteger();var delegate=context.getBean(TusharePageService.class);
            var counted=new TusharePageService(null) {
                @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> params,
                        PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                    calls.incrementAndGet();return delegate.execute(contract,params,consumer,validator,cancelled);
                }
            };
            var owner=new ThsIndexJobService(new QuestDbThsIndexTarget(table,jdbc),counted,path);var jobs=context.getBean(SyncJobRegistry.class);
            var groups=new StockBasicGroupService(jobs,context.getBean(StockBasicJobService.class),null,null,null,owner,path.toString());
            var day=LocalDate.of(2026,9,29);
            var plan=SyncGroupPlan.prepare(new SyncGroupRegistry(groups.definitions(),jobs),jobs,"group.ths_index_manual",1,
                    day,null,new SyncGroupPlan.Window(day,day),Map.of(),Map.of());
            var first=groups.runPlan(plan,null);assertEquals(SyncRunState.VERIFIED,first.state());
            assertEquals(1,calls.get());var before=new ThsIndexStorage(jdbc,table).snapshot();assertFalse(before.rows().isEmpty());
            var resumed=groups.runPlan(plan,first.runId());assertEquals(SyncRunState.VERIFIED,resumed.state());
            assertEquals(1,calls.get());String child=first.members().getFirst().childRunId();
            assertEquals(child,resumed.members().getFirst().childRunId());
            assertEquals(first.runId(),SyncRunLedger.openReadOnly(path).getRun(child).parentRunId());
            var actual=new ThsIndexStorage(jdbc,table).snapshot();assertEquals(before,actual);
            assertEquals(production,new ThsIndexStorage(jdbc,"ths_index").snapshot());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("group-readback.json").toFile(),
                    Map.of("first",first,"resumed",resumed,"actual",actual,"reusedChild",child,
                            "sourceCalls",calls.get(),"productionUnchanged",true));
            String backup=new ReferencePublicationJournal(path,"ths_index").forRun(child).intent().backup();
            jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+backup);
        }
    }
}
