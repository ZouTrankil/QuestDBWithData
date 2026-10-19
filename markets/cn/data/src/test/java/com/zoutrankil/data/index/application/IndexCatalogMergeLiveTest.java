package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.domain.policy.IndexCatalogMerge;

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
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_BOUNDED_READ",matches="1")
class IndexCatalogMergeLiveTest {
    @Test void realFileMergePreservesMissingCodesAndRepeatedInputWithoutWriting() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            var storage=new IndexCatalogStorage(ctx.getBean(JdbcTemplate.class),"index");var before=storage.snapshot();
            var source=new IndexCatalogFileSource().read(Path.of("artifacts/java-migration/D003/source-catalog.csv"),
                    Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
            var merged=IndexCatalogMerge.merge(before.businessRows(),source.rows());
            assertEquals(2274,before.rows().size());assertEquals(2343,source.rows().size());
            assertEquals(529,merged.inserted());assertEquals(460,merged.retainedAbsent());assertEquals(2803,merged.rows().size());
            assertEquals(source.rows().size(),merged.inserted()+merged.revised()+merged.unchanged());
            var current=new HashMap<String,com.zoutrankil.data.domain.IndexCatalogEntry>();
            merged.rows().forEach(row->current.put(row.indexCode(),row));
            for(var row:source.rows()) assertTrue(IndexCatalogMerge.sameBusinessValues(row,current.get(row.indexCode())));
            var repeated=IndexCatalogMerge.merge(merged.rows(),source.rows());
            assertFalse(repeated.requiresWrite());assertEquals(merged.rows(),repeated.rows());
            assertEquals(source.rows().size(),repeated.unchanged());
            var after=storage.snapshot();assertEquals(before.identity(),after.identity());assertEquals(before.rows(),after.rows());
            var report=new LinkedHashMap<String,Object>();report.put("readOnly",true);report.put("sourceFileHash",source.sha256());
            report.put("physicalBefore",before.identity());report.put("physicalFingerprint",before.fingerprint());
            report.put("sourceRows",source.rows().size());report.put("beforeRows",before.rows().size());
            report.put("plannedRows",merged.rows().size());report.put("inserted",merged.inserted());report.put("revised",merged.revised());
            report.put("unchanged",merged.unchanged());report.put("retainedAbsent",merged.retainedAbsent());
            report.put("repeatedRequiresWrite",repeated.requiresWrite());report.put("productionUnchanged",true);
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(
                    Path.of("artifacts/java-migration/D003/merge-readonly.json").toFile(),report);
        }
    }
}
