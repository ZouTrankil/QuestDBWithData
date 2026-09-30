package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.PageContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One read-only provider call freezes the L2 scope before any per-industry member requests. */
@EnabledIfEnvironmentVariable(named = "TUSHARE_PAGE_LIVE", matches = "1")
class IndexMembershipClassificationPreflightLiveTest {
    @Test void explicitSw2021L2ClassificationIsBoundedAndReceipted() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context -> context.addBeanFactoryPostProcessor(factory ->
                ((BeanDefinitionRegistry) factory).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var fields = List.of("index_code", "industry_name", "parent_code", "level", "industry_code", "is_pub", "src");
            var contract = new PageContract("index_classify", fields, List.of("index_code"),
                    Set.of("level", "src"), PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE,
                    null, null, 2000, 2000, 1, 2000,
                    "https://tushare.pro/document/2?doc_id=181; explicit SW2021 L2 with no documented paging");
            var rows = new ArrayList<Map<String, JsonNode>>();
            var result = context.getBean(TusharePageService.class).execute(contract,
                    Map.of("level", "L2", "src", "SW2021"), (page, receipt) -> rows.addAll(page.rows()), row -> {
                        assertEquals("L2", row.get("level").asText());
                        assertEquals("SW2021", row.get("src").asText());
                        assertTrue(row.get("index_code").asText().matches("[0-9]{6}\\.SI"));
                    }, () -> false);
            assertEquals(1, result.pages());
            assertTrue(rows.size() >= 100 && rows.size() < 2000);
            assertEquals(rows.size(), new HashSet<>(rows.stream().map(row -> row.get("index_code").asText()).toList()).size());
            Path folder = Path.of("artifacts/java-migration/D005", "classification-preflight-" + UUID.randomUUID());
            Files.createDirectories(folder);
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("source-review.json").toFile(),
                    Map.of("endpoint", "index_classify", "parameters", Map.of("level", "L2", "src", "SW2021"),
                            "fields", fields, "observedAt", Instant.now().toString(), "completion", result,
                            "rows", rows, "questdbWrites", 0));
        }
    }
}
