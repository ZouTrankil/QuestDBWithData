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
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class StockDetailGroupLiveTest {
    @Test void registeredGroupRunsSingleStaticOwnerAgainstIsolatedTable() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f)
                .removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");String target="java_d002_group_"+id;
            Path ledgerPath=Path.of("var","D002-group-"+id+".sqlite");
            Path evidence=Path.of("artifacts/java-migration/D002","group-"+id);
            var jdbc=ctx.getBean(JdbcTemplate.class);var columns=StockDetailInfoDataset.DEFINITION.columns();
            jdbc.execute("CREATE TABLE "+target+" ("+String.join(",",columns.stream()
                    .map(c->c.storageName()+" "+c.storageType().name()).toList())+")");
            String backup=null;boolean verified=false;
            try {
                var owner=new StockDetailInfoJobService(ctx.getBean(TusharePageService.class),jdbc,ledgerPath,target);
                var jobs=ctx.getBean(SyncJobRegistry.class);
                var groups=new StockBasicGroupService(jobs,ctx.getBean(StockBasicJobService.class),
                        ctx.getBean(ExchangeCalendarJobService.class),owner,ledgerPath.toString());
                var registry=new SyncGroupRegistry(groups.definitions(),jobs);
                var day=LocalDate.of(2026,9,29);
                var plan=SyncGroupPlan.prepare(registry,jobs,"group.stock_detail_manual",1,day,null,
                        new SyncGroupPlan.Window(day,day),Map.of("codes",List.of("000001.SZ"),"discover",false),Map.of());
                var result=groups.runPlan(plan,null);
                assertEquals(SyncRunState.VERIFIED,result.state());
                assertEquals(1,result.members().getFirst().verifiedRows());
                var child=result.members().getFirst().childRunId();
                var ledger=SyncRunLedger.openReadOnly(ledgerPath);
                assertEquals(result.runId(),ledger.getRun(child).parentRunId());
                assertEquals(SyncRunState.VERIFIED,ledger.get(child).state());
                var snapshot=new StockDetailInfoStorage(jdbc,target).snapshot();
                assertEquals(1,snapshot.rows().size());
                assertEquals("000001.SZ",snapshot.rows().getFirst().tsCode());
                var receipt=JobDefinitionJson.mapper().readTree(ledgerPath.getParent()
                        .resolve("sync-evidence").resolve(child).resolve("completion.json").toFile());
                backup=new StockDetailPublicationJournal(ledgerPath).get(receipt.path("publicationId").asText())
                        .intent().backup();
                Files.createDirectories(evidence);
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(
                        evidence.resolve("group-readback.json").toFile(),Map.of("target",target,
                                "ledger",ledgerPath.toString(),"result",result,"actual",snapshot));
                verified=true;
            } finally {
                if(verified) { jdbc.execute("DROP TABLE "+target);if(backup!=null) jdbc.execute("DROP TABLE "+backup); }
            }
        }
    }
}
