package com.zoutrankil.data.index.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.index.domain.ThsIndexState;
import com.zoutrankil.data.service.TusharePageService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@EnabledIfEnvironmentVariable(named = "TUSHARE_PAGE_LIVE", matches = "1")
class ThsIndexSourceLiveTest {
    @Test
    void oneExplicitCodeReturnsRealSourceWithoutQuestDbWrite() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(
                c ->
                        c.addBeanFactoryPostProcessor(
                                f ->
                                        ((BeanDefinitionRegistry) f)
                                                .removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            Path folder = Path.of("artifacts/java-migration/D004", "source-" + UUID.randomUUID());
            var instant = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var page =
                    new ThsIndexSource(context.getBean(TusharePageService.class), folder)
                            .fetch(
                                    new ThsIndexState.Scope("700001.TI", null, null),
                                    instant,
                                    () -> false);
            assertEquals(1, page.rows().size());
            var row = page.rows().getFirst();
            assertEquals("700001.TI", row.tsCode());
            assertEquals("A", row.exchange());
            assertEquals("BB", row.indexType());
            assertEquals(instant, row.observedAt());
            JobDefinitionJson.mapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(
                            folder.resolve("source-review.json").toFile(),
                            Map.of(
                                    "returnedRows",
                                    1,
                                    "rows",
                                    page.rows(),
                                    "sourceFingerprint",
                                    page.sourceFingerprint(),
                                    "questdbWrites",
                                    0,
                                    "scope",
                                    "one exact code; no pagination parameters"));
        }
    }
}
