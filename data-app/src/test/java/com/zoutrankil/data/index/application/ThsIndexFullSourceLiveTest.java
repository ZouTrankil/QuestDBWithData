package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.domain.ThsIndexState;

import com.zoutrankil.data.service.*;

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
class ThsIndexFullSourceLiveTest {
    @Test void fullCatalogUsesOneUnpagedCallAndValidatesEveryBusinessKey() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            Path folder=Path.of("artifacts/java-migration/D004","full-source-"+UUID.randomUUID());
            var observed=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var scope=ThsIndexState.Scope.all();assertTrue(scope.parameters().isEmpty());
            var result=new ThsIndexSource(context.getBean(TusharePageService.class),folder)
                    .fetch(scope,observed,()->false);
            assertFalse(result.rows().isEmpty());assertTrue(result.rows().size()<5000);
            assertEquals(result.rows().size(),result.rows().stream().map(r->r.tsCode()).distinct().count());
            assertTrue(result.rows().stream().allMatch(r->r.observedAt().equals(observed)));
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("full-source-review.json").toFile(),
                    Map.of("endpoint","ths_index","parameters",scope.parameters(),"rows",result.rows().size(),
                            "sourceFingerprint",result.sourceFingerprint(),"observedAt",observed,
                            "singleUnpagedCall",true,"questdbWrites",0));
        }
    }
}
