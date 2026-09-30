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
class IndexMembershipDiscoveryLiveTest {
    @Test void realClassificationFreezesIndustryNameForTwoSequentialMembershipRequests() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var pages=context.getBean(TusharePageService.class);
            Path folder=Path.of("artifacts/java-migration/D005","discovery-"+UUID.randomUUID());
            var catalog=new IndexMembershipClassificationSource(pages,folder).fetch(()->false);
            var scope=catalog.select(List.of("801011.SI"),IndexMembershipSource.Selection.BOTH).getFirst();
            assertEquals("林业Ⅱ",scope.industryName());
            var slice=new IndexMembershipSource(pages,folder).fetch(scope,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),()->false);
            assertFalse(slice.rows().isEmpty());assertTrue(slice.rows().stream().allMatch(r->r.indexName().equals(scope.industryName())));
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("discovery-readback.json").toFile(),
                    Map.of("catalog",catalog,"scope",scope,"membership",slice,"questdbWrites",0));
        }
    }
}
