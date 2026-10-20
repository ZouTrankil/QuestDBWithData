package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.stock.application.StockDetailInfoSource;

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
class StockDetailInfoSourceLiveTest {
    @Test
    void exactCodesCoverListedDelistedAndEmptyStatusesWithoutBulkPull() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(
                c ->
                        c.addBeanFactoryPostProcessor(
                                f ->
                                        ((BeanDefinitionRegistry) f)
                                                .removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            Path folder = Path.of("artifacts/java-migration/D002", "source-" + UUID.randomUUID());
            var source =
                    new StockDetailInfoSource(context.getBean(TusharePageService.class), folder);
            var observed = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var listed = source.fetch("000001.SZ", observed, () -> false);
            var delisted = source.fetch("000003.SZ", observed, () -> false);
            assertEquals(1, listed.rows().size());
            assertEquals(1, delisted.rows().size());
            assertEquals("L", listed.rows().getFirst().listStatus());
            assertEquals("D", delisted.rows().getFirst().listStatus());
            assertNull(listed.rows().getFirst().delistingDate());
            assertNotNull(delisted.rows().getFirst().delistingDate());
            JobDefinitionJson.mapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(
                            folder.resolve("source-validation.json").toFile(),
                            Map.of(
                                    "listed",
                                    listed,
                                    "delisted",
                                    delisted,
                                    "requests",
                                    6,
                                    "sourceRows",
                                    2,
                                    "writes",
                                    0));
        }
    }
}
