package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.service.IndexCatalogFileSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class IndexCatalogStagingLiveTest {
    @Test void mergedRealCatalogUsesBoundedWalBatchesAndExactAllColumnReadback() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            var jdbc=ctx.getBean(JdbcTemplate.class);var production=new IndexCatalogStorage(jdbc,"index");
            var before=production.snapshot();var source=new IndexCatalogFileSource().read(
                    Path.of("artifacts/java-migration/D003/source-catalog.csv"),
                    Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
            var prepared=IndexCatalogStaging.prepare(before,source.rows());
            var folder=Path.of("artifacts/java-migration/D003","stage-"+UUID.randomUUID());
            var stage=new IndexCatalogStaging(jdbc).write(prepared,folder,()->false);
            boolean verified=false;
            try {
                assertEquals(2803,stage.snapshot().rows().size());assertTrue(stage.batches()>1);
                assertEquals(prepared.rows(),stage.snapshot().rows());assertTrue(QuestDbWriteChecks.walSettled(jdbc,stage.table()));
                var repeated=IndexCatalogStaging.prepare(stage.snapshot(),source.rows());
                assertFalse(repeated.merge().requiresWrite());assertEquals(stage.snapshot().rows(),repeated.rows());
                assertThrows(IllegalArgumentException.class,()->new IndexCatalogStaging(jdbc).write(repeated,folder,()->false));
                var after=production.snapshot();assertEquals(before.identity(),after.identity());assertEquals(before.rows(),after.rows());
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("stage-readback.json").toFile(),
                        Map.of("sourceHash",source.sha256(),"sourceRows",source.rows().size(),"stage",stage,
                                "inserted",prepared.merge().inserted(),"revised",prepared.merge().revised(),
                                "retainedAbsent",prepared.merge().retainedAbsent(),"productionUnchanged",true,
                                "repeatedRequiresWrite",false));
                verified=true;
            } finally { if(verified) jdbc.execute("DROP TABLE "+stage.table()); }
        }
    }
}
