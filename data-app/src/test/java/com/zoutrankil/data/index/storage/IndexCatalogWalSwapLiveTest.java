package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.index.application.IndexCatalogFileSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test-owned full WAL layout cutover probe; no production publication. */
@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class IndexCatalogWalSwapLiveTest {
    @Test void renamedWalStageKeepsFullTargetAndPriorBackup() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);String nonce=UUID.randomUUID().toString().replace("-","");
            String target="java_d003_swap_"+nonce, backup="java_d003_backup_"+nonce;
            Path folder=Path.of("artifacts/java-migration/D003","wal-swap-"+nonce);
            jdbc.execute("CREATE TABLE "+target+" AS (SELECT * FROM \"index\") "
                    +"TIMESTAMP(import_time) PARTITION BY MONTH WAL");
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
            while(!QuestDbWriteChecks.walSettled(jdbc,target)) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Test-owned clone WAL did not settle");
                Thread.sleep(50);
            }
            var initial=new IndexCatalogStorage(jdbc,target).snapshot();
            assertEquals(2274,initial.rows().size());
            var input=new IndexCatalogFileSource().read(Path.of("artifacts/java-migration/D003/source-catalog.csv"),
                    Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
            var prepared=IndexCatalogStaging.prepare(initial,input.rows());
            var verified=new IndexCatalogStaging(jdbc).write(prepared,folder,()->false);
            assertEquals(2803,verified.snapshot().rows().size());
            jdbc.execute("RENAME TABLE "+target+" TO "+backup);
            jdbc.execute("RENAME TABLE "+verified.table()+" TO "+target);
            var published=new IndexCatalogStorage(jdbc,target).snapshot();
            var retained=new IndexCatalogStorage(jdbc,backup).snapshot();
            assertEquals(verified.snapshot().identity(),published.identity());
            assertEquals(verified.snapshot().fingerprint(),published.fingerprint());
            assertEquals(verified.snapshot().rows(),published.rows());
            assertEquals(initial.identity(),retained.identity());
            assertEquals(initial.fingerprint(),retained.fingerprint());
            assertEquals(initial.rows(),retained.rows());
            assertEquals(2274,new IndexCatalogStorage(jdbc,"index").snapshot().rows().size());
            Files.createDirectories(folder);
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("wal-swap-readback.json").toFile(),
                    Map.of("testOwnedTarget",target,"backup",backup,"stageIdentity",verified.snapshot().identity(),
                            "publishedIdentity",published.identity(),"beforeFingerprint",initial.fingerprint(),
                            "afterFingerprint",published.fingerprint(),"beforeRows",initial.rows().size(),
                            "afterRows",published.rows().size(),"productionReadOnly",true));
            jdbc.execute("DROP TABLE "+backup);
            jdbc.execute("DROP TABLE "+target);
        }
    }
}
