package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class IndexMembershipSourceLiveTest {
    @Test void oneFrozenIndustryProducesCompleteRealCurrentAndHistoricalSlice() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            Path folder=Path.of("artifacts/java-migration/D005","source-adapter-"+UUID.randomUUID());
            var scope=new IndexMembershipSource.Scope("801011.SI","林业Ⅱ",IndexMembershipSource.Selection.BOTH);
            var observed=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var result=new IndexMembershipSource(context.getBean(TusharePageService.class),folder).fetch(scope,observed,()->false);
            assertFalse(result.rows().isEmpty());assertTrue(result.rows().size()<4000);
            assertTrue(result.rows().stream().anyMatch(r->r.latestFlag().equals("Y")));
            assertTrue(result.rows().stream().anyMatch(r->r.latestFlag().equals("N")));
            assertEquals(result.rows().size(),result.rows().stream().map(r->r.key()).distinct().count());
            assertTrue(result.rows().stream().allMatch(r->r.observedAt().equals(observed)));
            byte[] bytes=Files.readAllBytes(Path.of(result.responseEvidence()));
            assertEquals(result.sourceFingerprint(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("source-review.json").toFile(),
                    Map.of("result",result,"currentRows",result.rows().stream().filter(r->r.latestFlag().equals("Y")).count(),
                            "historicalRows",result.rows().stream().filter(r->r.latestFlag().equals("N")).count(),"questdbWrites",0));
        }
    }
}
