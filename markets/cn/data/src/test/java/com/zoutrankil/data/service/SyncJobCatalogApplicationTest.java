package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.application.StockBasicSyncService;
import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;

import com.zoutrankil.data.QuestDataApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncJobCatalogApplicationTest {
    @org.junit.jupiter.api.io.TempDir Path outputFolder;
    @Test void realApplicationCommandResolvesOwnerAndSerializesVersionedCatalog() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("show-sync-job-definitions")) {
            var registry = context.getBean(SyncJobRegistry.class);
            var job = registry.require("data.stock_basic", 2);
            assertEquals(context.getBean(StockBasicSyncService.class).datasetId(), job.datasetId());
            assertTrue(job.enabled());
            assertFalse(job.dailyEligible());
            assertTrue(registry.dailyJobs().stream().noneMatch(daily -> daily.jobId().equals(job.jobId())));
            var calendar = registry.require("data.exchange_calendar", 1);
            assertEquals(context.getBean(ExchangeCalendarJobService.class).datasetId(), calendar.datasetId());
            assertTrue(registry.dailyJobs().contains(calendar));
            Path output = outputFolder.resolve("job-definitions.json");
            Files.createDirectories(output.getParent());
            com.zoutrankil.data.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter()
                    .writeValue(output.toFile(), registry.definitions());
        }
    }
}
