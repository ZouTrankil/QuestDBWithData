package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.application.StockBasicJobService;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.ThsIndexMapper;
import com.zoutrankil.data.repository.*;
import io.questdb.client.QuestDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class ThsIndexPreparedWriteLiveTest {
    @Test void typedWriteGroupPublishesFullRowsAndSameInputIsReused() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);String nonce=UUID.randomUUID().toString().replace("-","");
            String table="java_d004_prepared_"+nonce;Path folder=Path.of("artifacts/java-migration/D004","prepared-"+nonce);
            Files.createDirectories(folder);Path path=folder.resolve("ledger.sqlite");
            jdbc.execute("CREATE TABLE \""+table+"\" (ts_code SYMBOL,name STRING,\"count\" INT,exchange STRING,"
                    +"list_date STRING,\"type\" STRING,update_time TIMESTAMP) TIMESTAMP(update_time) "
                    +"PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)");
            var owner=new ThsIndexJobService(jdbc,context.getBean(TusharePageService.class),path,table);
            var datasets=context.getBean(DatasetRegistry.class);
            var service=new StockBasicWriteGroupService(datasets,context.getBean(StockBasicJobService.class),
                    null,null,null,owner,jdbc,context.getBean(QuestDB.class),path.toString());
            var rows=new ThsIndexStorage(jdbc,"ths_index").snapshot().businessRows().subList(0,2);
            var mapper=new ThsIndexMapper();Path input=folder.resolve("write-group.json");
            JobDefinitionJson.mapper().writeValue(input.toFile(),Map.of("batchId","ths-sample",
                    "logicalDate","2026-09-29","members",List.of(Map.of("memberId","ths-member",
                    "datasetId","ths_index","definitionVersion",ThsIndexDataset.DEFINITION.schemaVersion(),
                    "batchId","ths-rows","rows",rows.stream().map(r->mapper.values(r).asMap()).toList()))));
            var first=service.run(input,null);assertEquals(SyncRunState.VERIFIED,first.state());
            var actual=new ThsIndexStorage(jdbc,table).snapshot();assertEquals(2,actual.rows().size());
            for(var row:rows) assertTrue(actual.businessRows().contains(row));
            var child=first.members().getFirst().childRunId();var previous=new SyncRunLedger(path).getRun(child);
            var plan=WriteGroupPlan.prepare(new WriteGroupJson(datasets).read(input),datasets,
                    Map.of("ths_index",previous.targetId()));
            var adapter=new ThsIndexPreparedWriteAdapter(plan,"ths-member",owner,folder,true);
            owner.revalidateGroupChild(child,previous.targetId(),adapter.request());
            var second=service.run(input,first.runId());assertEquals(SyncRunState.VERIFIED,second.state());
            assertEquals(child,second.members().getFirst().childRunId());assertTrue(second.members().getFirst().reused());
            assertEquals(actual,new ThsIndexStorage(jdbc,table).snapshot());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("write-readback.json").toFile(),
                    Map.of("first",first,"second",second,"actual",actual,"input",input.toString(),"productionWrites",0));
            String backup=new ReferencePublicationJournal(path,"ths_index").forRun(child).intent().backup();
            jdbc.execute("DROP TABLE \""+table+"\"");jdbc.execute("DROP TABLE \""+backup+"\"");
        }
    }
}
