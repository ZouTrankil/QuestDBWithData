package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.storage.QuestDbIndexCatalogTarget;

import com.zoutrankil.data.index.storage.IndexCatalogStorage;

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
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class IndexCatalogGroupLiveTest {
    @Test void registeredGroupPublishesAndResumesWithFrozenOriginalTargetAndFullReadback() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);String nonce=UUID.randomUUID().toString().replace("-","");
            String table="java_d003_group_"+nonce;Path folder=Path.of("artifacts/java-migration/D003","group-"+nonce);
            Files.createDirectories(folder);Path path=folder.resolve("ledger.sqlite");
            jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",IndexCatalogDataset.DEFINITION.columns().stream()
                    .map(c->c.storageName()+" "+c.storageType().name()).toList())+") TIMESTAMP(import_time) PARTITION BY MONTH WAL");
            var owner=new IndexCatalogJobService(new QuestDbIndexCatalogTarget(jdbc,table),path);var jobs=context.getBean(SyncJobRegistry.class);
            var groups=new StockBasicGroupService(jobs,context.getBean(StockBasicJobService.class),null,null,owner,path.toString());
            var day=LocalDate.of(2026,9,29);var request=owner.plan(Path.of("artifacts/java-migration/D003/source-catalog.csv"),day);
            var plan=SyncGroupPlan.prepare(new SyncGroupRegistry(groups.definitions(),jobs),jobs,"group.index_catalog_manual",1,
                    day,null,new SyncGroupPlan.Window(day,day),request.parameters(),Map.of());
            var first=groups.runPlan(plan,null);assertEquals(SyncRunState.VERIFIED,first.state());
            var before=new IndexCatalogStorage(jdbc,table).snapshot();assertEquals(2343,before.rows().size());
            var second=groups.runPlan(plan,first.runId());assertEquals(SyncRunState.VERIFIED,second.state());
            assertEquals(first.members().getFirst().childRunId(),second.members().getFirst().childRunId());
            var actual=new IndexCatalogStorage(jdbc,table).snapshot();assertEquals(before,actual);
            var ledger=SyncRunLedger.openReadOnly(path);String child=first.members().getFirst().childRunId();
            assertEquals(first.runId(),ledger.getRun(child).parentRunId());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("group-readback.json").toFile(),
                    Map.of("first",first,"resumed",second,"actual",actual,"reusedChild",child));
            String backup=new ReferencePublicationJournal(path,"index").forRun(child).intent().backup();
            jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+backup);
        }
    }
}
