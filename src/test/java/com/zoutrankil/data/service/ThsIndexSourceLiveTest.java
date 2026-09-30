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
class ThsIndexSourceLiveTest {
    @Test void oneExplicitCodeReturnsRealSourceWithoutQuestDbWrite() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            Path folder=Path.of("artifacts/java-migration/D004","source-"+UUID.randomUUID());
            var instant=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var page=new ThsIndexSource(context.getBean(TusharePageService.class),folder)
                    .fetch(new ThsIndexSource.Scope("700001.TI",null,null),instant,()->false);
            assertEquals(1,page.rows().size());var row=page.rows().getFirst();assertEquals("700001.TI",row.tsCode());
            assertEquals("A",row.exchange());assertEquals("BB",row.indexType());assertEquals(instant,row.observedAt());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("source-review.json").toFile(),
                    Map.of("returnedRows",1,"rows",page.rows(),"sourceFingerprint",page.sourceFingerprint(),
                            "questdbWrites",0,"scope","one exact code; no pagination parameters"));
        }
    }
}
