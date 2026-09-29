package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncJobCatalogApplicationTest {
    @Test void realApplicationCommandResolvesOwnerAndSerializesVersionedCatalog() throws Exception {
        var app = new SpringApplication(QuestDbWithDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("show-sync-job-definitions")) {
            var registry = context.getBean(SyncJobRegistry.class);
            var job = registry.require("data.stock_basic", 1);
            assertEquals(context.getBean(StockBasicSyncService.class).datasetId(), job.datasetId());
            assertFalse(job.enabled());
            assertFalse(job.dailyEligible());
            assertTrue(registry.dailyJobs().isEmpty());
            Path output = Path.of("artifacts/java-migration/F009/job-definitions.json");
            Files.createDirectories(output.getParent());
            com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter()
                    .writeValue(output.toFile(), registry.definitions());
        }
    }
}
