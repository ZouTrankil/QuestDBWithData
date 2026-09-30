package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class StockDetailOwnerResumeLiveTest {
    @Test void sourceFailureBeforeSubmissionCanReplayExactScopeAndPublishOnce() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f)
                .removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");String target="java_d002_resume_"+id;
            Path ledgerPath=Path.of("var","D002-resume-"+id+".sqlite");
            Path evidence=Path.of("artifacts/java-migration/D002","resume-"+id);
            var jdbc=ctx.getBean(JdbcTemplate.class);var columns=StockDetailInfoDataset.DEFINITION.columns();
            jdbc.execute("CREATE TABLE "+target+" ("+String.join(",",columns.stream()
                    .map(c->c.storageName()+" "+c.storageType().name()).toList())+")");
            String backup=null;boolean verified=false;
            try {
                var failedPages=mock(TusharePageService.class);
                doThrow(new IOException("injected source failure")).when(failedPages).execute(any(),anyMap(),
                        any(),any(),any());
                var failing=new StockDetailInfoJobService(failedPages,jdbc,ledgerPath,target);
                var plan=failing.plan(List.of("000001.SZ"),false,LocalDate.of(2026,9,29));
                var first=failing.run(plan);
                assertEquals(SyncRunState.FAILED,first.state());
                assertEquals(0,new StockDetailInfoStorage(jdbc,target).snapshot().rows().size());
                assertNull(new DatasetIntervalLock(ledgerPath).findOwned(first.runId(),
                        DatasetIntervalLock.Scope.allDates("stock_detail_info")));
                var owner=new StockDetailInfoJobService(ctx.getBean(TusharePageService.class),jdbc,ledgerPath,target);
                var replay=owner.resume(plan,first.runId());
                assertEquals(SyncRunState.VERIFIED,replay.state(),replay.errorCode());
                assertEquals(1,replay.insertedRows());
                assertEquals(1,new StockDetailInfoStorage(jdbc,target).snapshot().rows().size());
                assertThrows(IllegalStateException.class,()->owner.resume(plan,replay.runId()));
                var receipt=JobDefinitionJson.mapper().readTree(Path.of(replay.evidence()).toFile());
                backup=new StockDetailPublicationJournal(ledgerPath).get(receipt.path("publicationId").asText())
                        .intent().backup();
                Files.createDirectories(evidence);
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(
                        evidence.resolve("resume-readback.json").toFile(),Map.of("target",target,
                                "ledger",ledgerPath.toString(),"failed",first,"replay",replay,
                                "resumeIntent",ledgerPath.getParent().resolve("sync-evidence")
                                        .resolve(replay.runId()).resolve("resume-intent.json").toString()));
                verified=true;
            } finally {
                if(verified) { jdbc.execute("DROP TABLE "+target);if(backup!=null) jdbc.execute("DROP TABLE "+backup); }
            }
        }
    }
}
