package com.zoutrankil.data.service;
import com.zoutrankil.data.stock.storage.QuestDbStockDetailTarget;

import com.zoutrankil.data.stock.application.StockDetailInfoRunRecovery;
import com.zoutrankil.data.stock.storage.StockDetailInfoStorage;
import com.zoutrankil.data.stock.storage.StockDetailPublicationJournal;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit local recovery audit; enable only after the original isolated-test process is confirmed terminal. */
@EnabledIfEnvironmentVariable(named="D002_RECOVERY_LEDGER",matches=".+")
class StockDetailObservedRunRecoveryTest {
    @Test void stoppedObservedRunRecoversFromActualRetainedTables() throws Exception {
        String run=Objects.requireNonNull(System.getenv("D002_RECOVERY_RUN"));
        Path path=Path.of(System.getenv("D002_RECOVERY_LEDGER"));var ledger=SyncRunLedger.openReadOnly(path);
        assertEquals(SyncRunState.IN_DOUBT,ledger.get(run).state());
        Path completion=path.toAbsolutePath().getParent().resolve("sync-evidence").resolve(run).resolve("completion.json");
        var proof=JobDefinitionJson.mapper().readTree(completion.toFile());
        var publication=new StockDetailPublicationJournal(path).get(proof.path("publicationId").asText());
        String table=publication.intent().target();
        assertTrue(table.startsWith("java_d002_owner_") || table.startsWith("java_d002_slice_recovery_"));
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            var jdbc=ctx.getBean(JdbcTemplate.class);
            assertThrows(IllegalStateException.class,()->StockDetailInfoRunRecovery.reconcilePublished(new QuestDbStockDetailTarget(jdbc,table),path,table,run,false));
            var result=StockDetailInfoRunRecovery.reconcilePublished(new QuestDbStockDetailTarget(jdbc,table),path,table,run,true);
            assertEquals(SyncRunState.VERIFIED,result.state());assertEquals(SyncRunState.VERIFIED,ledger.get(run).state());
            assertEquals(SyncRunState.VERIFIED,ledger.get(run+"-attempt").state());
            if(proof.has("snapshotSlice")) assertEquals(SyncRunState.VERIFIED,ledger.get(proof.path("snapshotSlice").asText()).state());
            assertNull(new DatasetIntervalLock(path).findOwned(run,DatasetIntervalLock.Scope.allDates("stock_detail_info")));
            var actual=new StockDetailInfoStorage(jdbc,table).snapshot();
            Path evidence=Path.of("artifacts/java-migration/D002","observed-recovery-"+run+".json");
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(),
                    Map.of("result",result,"actual",actual,"table",table,"writerProof",
                            Objects.requireNonNull(System.getenv("D002_RECOVERY_WRITER_PROOF")),
                            "newSourceRequests",0,"questdbWrites",0));
        }
    }
}
