package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRunState;
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

/** Full candidate-file owner acceptance on a clone; production is read only. */
@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class IndexCatalogOwnerAcceptanceLiveTest {
    @Test void fullFileIncrementalPublishesThenExactRerunKeepsPhysicalIdentity() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);
            String nonce=UUID.randomUUID().toString().replace("-","");
            String target="java_d003_owner_"+nonce;
            Path folder=Path.of("artifacts/java-migration/D003","owner-"+nonce);
            Path ledger=folder.resolve("ledger.sqlite");Files.createDirectories(folder);
            var production=new IndexCatalogStorage(jdbc,"index").snapshot();
            jdbc.execute("CREATE TABLE "+target+" AS (SELECT * FROM \"index\") "
                    +"TIMESTAMP(import_time) PARTITION BY MONTH WAL");
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
            while(!QuestDbWriteChecks.walSettled(jdbc,target)) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Owner clone WAL did not settle");
                Thread.sleep(50);
            }
            var before=new IndexCatalogStorage(jdbc,target).snapshot();
            assertEquals(production.rows(),before.rows());
            var owner=new IndexCatalogJobService(jdbc,ledger,target);
            var request=owner.plan(Path.of("artifacts/java-migration/D003/source-catalog.csv"),LocalDate.of(2026,9,29));
            var first=owner.run(request);
            assertEquals(SyncRunState.VERIFIED,first.state(),first.errorCode());
            assertEquals(2343,first.sourceRows());assertEquals(529,first.inserted());
            assertEquals(1814,first.revised());assertEquals(460,first.retainedAbsent());
            assertEquals(2343,first.verifiedRows());
            var after=new IndexCatalogStorage(jdbc,target).snapshot();
            assertEquals(2803,after.rows().size());
            var journal=new ReferencePublicationJournal(ledger,"index");
            var backupName=journal.forRun(first.runId()).intent().backup();
            var backup=new IndexCatalogStorage(jdbc,backupName).snapshot();
            assertEquals(before.rows(),backup.rows());
            assertEquals(before.identity(),backup.identity());
            var second=owner.run(owner.plan(Path.of("artifacts/java-migration/D003/source-catalog.csv"),LocalDate.of(2026,9,29)));
            assertEquals(SyncRunState.VERIFIED,second.state(),second.errorCode());
            assertNull(second.publicationId());
            assertEquals(0,second.inserted());assertEquals(0,second.revised());
            assertEquals(2343,second.unchanged());
            assertEquals(after.identity(),new IndexCatalogStorage(jdbc,target).snapshot().identity());
            assertEquals(production.identity(),new IndexCatalogStorage(jdbc,"index").snapshot().identity());
            assertEquals(production.rows(),new IndexCatalogStorage(jdbc,"index").snapshot().rows());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("owner-readback.json").toFile(),
                    Map.of("first",first,"second",second,"before",before.identity(),"after",after.identity(),
                            "beforeFingerprint",before.fingerprint(),"afterFingerprint",after.fingerprint(),
                            "backup",backupName,"backupVerified",true,"productionUnchanged",true));
            jdbc.execute("DROP TABLE "+backupName);jdbc.execute("DROP TABLE "+target);
        }
    }
}
