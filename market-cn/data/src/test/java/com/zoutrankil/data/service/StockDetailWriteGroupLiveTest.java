package com.zoutrankil.data.service;
import com.zoutrankil.data.stock.storage.QuestDbStockDetailTarget;

import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockDetailInfoJobService;
import com.zoutrankil.data.stock.domain.policy.StockDetailInfoMerge;
import com.zoutrankil.data.stock.storage.StockDetailInfoStorage;
import com.zoutrankil.data.stock.storage.StockDetailPublicationJournal;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.mapper.StockDetailInfoMapper;
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
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class StockDetailWriteGroupLiveTest {
    @Test void preparedStaticMemberPublishesExactlyOnceAndReplaysWithoutReplacement() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f)
                .removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");String target="java_d002_write_group_"+id;
            Path ledgerPath=Path.of("var","D002-write-group-"+id+".sqlite");
            Path evidence=Path.of("artifacts/java-migration/D002","write-group-"+id);
            var jdbc=ctx.getBean(JdbcTemplate.class);
            var productionBefore=new StockDetailInfoStorage(jdbc,"stock_detail_info").snapshot();
            var sourceRow=productionBefore.businessRows().getFirst();
            var columns=StockDetailInfoDataset.DEFINITION.columns();
            jdbc.execute("CREATE TABLE "+target+" ("+String.join(",",columns.stream()
                    .map(c->c.storageName()+" "+c.storageType().name()).toList())+")");
            String backup=null;boolean verified=false;
            try {
                Files.createDirectories(evidence);Path request=evidence.resolve("prepared-write.json");
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(request.toFile(),Map.of(
                        "batchId","stock_detail_group_"+id,"logicalDate","2026-09-29",
                        "members",List.of(Map.of("memberId","stock_detail","datasetId","stock_detail_info",
                                "definitionVersion",1,"batchId","stock_detail_member_"+id,
                                "rows",List.of(new StockDetailInfoMapper().values(sourceRow).asMap())))));
                var owner=new StockDetailInfoJobService(ctx.getBean(TusharePageService.class),new QuestDbStockDetailTarget(jdbc,target),ledgerPath);
                var group=new StockBasicWriteGroupService(ctx.getBean(DatasetRegistry.class),
                        mock(StockBasicJobService.class),null,owner,new com.zoutrankil.data.group.storage.QuestDbWriteGroupWriters(jdbc,ctx.getBean(QuestDB.class)),
                        ledgerPath.toString());
                var first=group.run(request,null);assertEquals(SyncRunState.VERIFIED,first.state());
                var afterFirst=new StockDetailInfoStorage(jdbc,target).snapshot();
                assertEquals(1,afterFirst.rows().size());
                assertEquals(sourceRow.key(),afterFirst.businessRows().getFirst().key());
                assertTrue(StockDetailInfoMerge.sameBusinessValues(sourceRow,afterFirst.businessRows().getFirst()));
                var child=first.members().getFirst().childRunId();
                var receipt=JobDefinitionJson.mapper().readTree(ledgerPath.getParent()
                        .resolve("sync-evidence").resolve(child).resolve("completion.json").toFile());
                backup=new StockDetailPublicationJournal(ledgerPath).get(receipt.path("publicationId").asText())
                        .intent().backup();
                var second=group.run(request,null);assertEquals(SyncRunState.VERIFIED,second.state());
                var afterSecond=new StockDetailInfoStorage(jdbc,target).snapshot();
                assertEquals(afterFirst.identity(),afterSecond.identity());
                assertEquals(afterFirst.rows(),afterSecond.rows());
                var ledger=SyncRunLedger.openReadOnly(ledgerPath);
                for(var run:List.of(first,second)) {
                    assertEquals(SyncRunState.VERIFIED,ledger.get(run.runId()).state());
                    assertEquals(SyncRunState.VERIFIED,ledger.get(run.members().getFirst().childRunId()).state());
                    var slice=ledger.get(run.members().getFirst().childRunId()+"-snapshot");
                    assertEquals(SyncRunState.VERIFIED,slice.state());
                    assertEquals(SyncRunLedger.Kind.SLICE,slice.kind());
                    assertEquals(run.members().getFirst().childRunId()+"-attempt",slice.parentId());
                }
                var productionAfter=new StockDetailInfoStorage(jdbc,"stock_detail_info").snapshot();
                assertEquals(productionBefore.identity(),productionAfter.identity());
                assertEquals(productionBefore.fingerprint(),productionAfter.fingerprint());
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(
                        evidence.resolve("write-group-readback.json").toFile(),Map.of("target",target,
                                "ledger",ledgerPath.toString(),"first",first,"second",second,
                                "actual",afterSecond,"productionUnchanged",true,"sourceKind","production-readback-copy"));
                verified=true;
            } finally {
                if(verified) { jdbc.execute("DROP TABLE "+target);if(backup!=null) jdbc.execute("DROP TABLE "+backup); }
            }
        }
    }
}
