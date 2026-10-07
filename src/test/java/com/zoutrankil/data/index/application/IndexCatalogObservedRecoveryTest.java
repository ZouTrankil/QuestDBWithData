package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.storage.QuestDbIndexCatalogTarget;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.index.storage.IndexCatalogStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** One-time recovery of the retained test-owned OLD_MOVED layout from an interrupted live probe. */
@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class IndexCatalogObservedRecoveryTest {
    @Test void finishRetainedOldMovedProbeThenRemoveOnlyItsTestTables() throws Exception {
        String nonce="1a8c8ca082fe419c8b24051a04294063";
        String run="catalog-publish-true-"+nonce;
        Path ledgerPath=Path.of("var","D003-publication-"+nonce+".sqlite");
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);
            var locks=new DatasetIntervalLock(ledgerPath);
            var lease=locks.findOwned(run,DatasetIntervalLock.Scope.allDates("index"));
            assertNotNull(lease);
            var publication=new IndexCatalogPublication(new QuestDbIndexCatalogTarget(jdbc,"index"),ledgerPath);
            assertEquals(IndexCatalogPublication.Layout.OLD_MOVED,publication.inspect(run));
            var result=publication.finish(lease,true);
            assertEquals(IndexCatalogPublication.Layout.PUBLISHED,publication.inspect(run));
            assertEquals(2,result.actual().rows().size());
            var backup=new IndexCatalogStorage(jdbc,result.publication().intent().backup()).snapshot();
            assertEquals(0,backup.rows().size());
            Path evidence=Path.of("artifacts/java-migration/D003/observed-recovery.json");
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(),
                    Map.of("runId",run,"layoutBefore","OLD_MOVED","layoutAfter","PUBLISHED",
                            "publishedIdentity",result.actual().identity(),"publishedRows",result.actual().rows().size(),
                            "backupIdentity",backup.identity(),"backupRows",backup.rows().size(),
                            "stoppedWriterProof","previous dedicated Gradle test worker had exited"));
            locks.releaseAfterReconciliation(locks.findOwned(run,lease.scope()),true,true);
            jdbc.execute("DROP TABLE "+result.publication().intent().target());
            jdbc.execute("DROP TABLE "+result.publication().intent().backup());
        }
    }
}
