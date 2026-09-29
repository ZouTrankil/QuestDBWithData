package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.IndexCatalogMapper;
import com.zoutrankil.questdbwithdata.repository.*;
import io.questdb.client.QuestDB;
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
class IndexCatalogPreparedWriteLiveTest {
    @Test void typedWriteGroupPublishesFullRowsAndSameInputIsIdempotent() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);String nonce=UUID.randomUUID().toString().replace("-","");
            String table="java_d003_prepared_"+nonce;Path folder=Path.of("artifacts/java-migration/D003","prepared-"+nonce);
            Files.createDirectories(folder);Path path=folder.resolve("ledger.sqlite");
            jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",IndexCatalogDataset.DEFINITION.columns().stream()
                    .map(c->c.storageName()+" "+c.storageType().name()).toList())+") TIMESTAMP(import_time) PARTITION BY MONTH WAL");
            var owner=new IndexCatalogJobService(jdbc,path,table);
            var service=new StockBasicWriteGroupService(context.getBean(DatasetRegistry.class),
                    context.getBean(StockBasicJobService.class),null,null,owner,jdbc,
                    context.getBean(QuestDB.class),path.toString());
            var source=new IndexCatalogFileSource().read(Path.of("artifacts/java-migration/D003/source-catalog.csv"),
                    Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
            var rows=source.rows().subList(0,2);var mapper=new IndexCatalogMapper();
            Path input=folder.resolve("write-group.json");
            JobDefinitionJson.mapper().writeValue(input.toFile(),Map.of("batchId","catalog-sample",
                    "logicalDate","2026-09-29","members",List.of(Map.of("memberId","index-member",
                    "datasetId","index","definitionVersion",IndexCatalogDataset.DEFINITION.schemaVersion(),
                    "batchId","catalog-rows","rows",rows.stream().map(r->mapper.values(r).asMap()).toList()))));
            var first=service.run(input,null);assertEquals(SyncRunState.VERIFIED,first.state());
            var actual=new IndexCatalogStorage(jdbc,table).snapshot();assertEquals(2,actual.rows().size());
            for(var row:rows) assertTrue(actual.businessRows().contains(row));
            var child=first.members().getFirst().childRunId();
            var previous=new SyncRunLedger(path).getRun(child);
            var plan=WriteGroupPlan.prepare(new WriteGroupJson(context.getBean(DatasetRegistry.class)).read(input),
                    context.getBean(DatasetRegistry.class),Map.of("index",previous.targetId()));
            var adapter=new IndexCatalogPreparedWriteAdapter(plan,"index-member",owner,folder,true);
            owner.revalidateGroupChild(child,previous.targetId(),adapter.request());
            var second=service.run(input,first.runId());assertEquals(SyncRunState.VERIFIED,second.state());
            assertEquals(first.members().getFirst().childRunId(),second.members().getFirst().childRunId());
            assertTrue(second.members().getFirst().reused());
            assertEquals(actual,new IndexCatalogStorage(jdbc,table).snapshot());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("write-readback.json").toFile(),
                    Map.of("first",first,"second",second,"actual",actual,"input",input.toString()));
            String backup=new ReferencePublicationJournal(path,"index").forRun(first.members().getFirst().childRunId()).intent().backup();
            jdbc.execute("DROP TABLE "+table);jdbc.execute("DROP TABLE "+backup);
        }
    }
}
