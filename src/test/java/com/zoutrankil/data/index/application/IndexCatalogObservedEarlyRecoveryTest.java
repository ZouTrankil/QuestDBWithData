package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.storage.QuestDbIndexCatalogTarget;

import com.zoutrankil.data.index.storage.IndexCatalogStorage;

import com.zoutrankil.data.service.*;

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

/** Explicit retained failure scene only; writer termination must be established by the caller. */
@EnabledIfEnvironmentVariable(named="D003_EARLY_RECOVERY_LEDGER",matches=".+")
class IndexCatalogObservedEarlyRecoveryTest {
    @Test void recoverPreviouslyFailedStageEvidenceComparisonWithoutResubmittingRows() throws Exception {
        Path path=Path.of(System.getenv("D003_EARLY_RECOVERY_LEDGER")).toAbsolutePath().normalize();
        String run=System.getenv("D003_EARLY_RECOVERY_RUN"),table=System.getenv("D003_EARLY_RECOVERY_TABLE");
        String writerProof=System.getenv("D003_EARLY_WRITER_PROOF");assertNotNull(writerProof);assertFalse(writerProof.isBlank());
        assertTrue(table.startsWith("java_d003_early_"));assertTrue(Files.isRegularFile(path));
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            var ledger=SyncRunLedger.openReadOnly(path);assertEquals(SyncRunState.IN_DOUBT,ledger.get(run).state());
            var before=new IndexCatalogStorage(jdbc,table).snapshot();assertTrue(before.rows().isEmpty());
            Path evidence=path.getParent().resolve("sync-evidence").resolve(run);List<Path> intents;
            try(var paths=Files.list(evidence)) { intents=paths.filter(p->p.getFileName().toString().endsWith("-intent.json")).toList(); }
            assertEquals(1,intents.size());String stage=json.readTree(intents.getFirst().toFile()).path("stage").asText();
            var staged=new IndexCatalogStorage(jdbc,stage).snapshot();assertEquals(2343,staged.rows().size());
            var owner=new IndexCatalogJobService(new QuestDbIndexCatalogTarget(jdbc,table),path);var result=owner.finishInterrupted(run,true);
            assertEquals(SyncRunState.VERIFIED,result.state());var actual=new IndexCatalogStorage(jdbc,table).snapshot();
            assertEquals(staged,actual);assertEquals(stage,new ReferencePublicationJournal(path,"index").forRun(run).intent().stage());
            json.writerWithDefaultPrettyPrinter().writeValue(path.getParent().resolve("observed-recovery-readback.json").toFile(),
                    Map.of("result",result,"before",before,"actual",actual,"writerStoppedProof",writerProof,
                            "rowInsertsDuringRecovery",0,"sourceRequestsDuringRecovery",0,"retainedTargetAndBackup",true));
        }
    }
}
