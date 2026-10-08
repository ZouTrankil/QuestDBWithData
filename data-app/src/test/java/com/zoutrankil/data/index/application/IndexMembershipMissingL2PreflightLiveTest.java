package com.zoutrankil.data.index.application;

import com.zoutrankil.data.service.*;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Read-only diagnosis of three source L2 codes absent from the current physical table. */
@EnabledIfEnvironmentVariable(named = "TUSHARE_PAGE_LIVE", matches = "1")
class IndexMembershipMissingL2PreflightLiveTest {
    @Test void absentPhysicalIndustriesHaveExplicitCurrentAndHistoricalSourceReceipts() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context -> context.addBeanFactoryPostProcessor(factory ->
                ((BeanDefinitionRegistry) factory).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var fields = List.of("l1_code", "l1_name", "l2_code", "l2_name", "l3_code", "l3_name",
                    "ts_code", "name", "in_date", "out_date", "is_new");
            var contract = new PageContract("index_member_all", fields,
                    List.of("l2_code", "l3_code", "ts_code", "in_date"), Set.of("l2_code", "is_new"),
                    PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE,
                    null, null, 2000, 2000, 1, 2000,
                    "https://tushare.pro/document/2?doc_id=335; explicit Y/N, no documented paging");
            var folder = Path.of("artifacts/java-migration/D005", "missing-l2-preflight-" + UUID.randomUUID());
            Files.createDirectories(folder);
            var summary = new ArrayList<Map<String, Object>>();
            var pages = context.getBean(TusharePageService.class);
            for (var code : List.of("801217.SI", "801768.SI", "801786.SI")) {
                for (var flag : List.of("Y", "N")) {
                    var params = Map.<String, Object>of("l2_code", code, "is_new", flag);
                    var rows = new ArrayList<Map<String, JsonNode>>();
                    var completed = pages.execute(contract, params, (page, receipt) -> rows.addAll(page.rows()), row -> {
                        assertEquals(code, row.get("l2_code").asText());
                        assertEquals(flag, row.get("is_new").asText());
                    }, () -> false);
                    assertEquals(1, completed.pages());
                    assertTrue(rows.size() < 2000);
                    var proof = new LinkedHashMap<String, Object>();
                    proof.put("endpoint", "index_member_all");
                    proof.put("parameters", params);
                    proof.put("fields", fields);
                    proof.put("observedAt", Instant.now().toString());
                    proof.put("completion", completed);
                    proof.put("rows", rows);
                    proof.put("questdbWrites", 0);
                    JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter()
                            .writeValue(folder.resolve(code + "-" + flag + ".json").toFile(), proof);
                    summary.add(Map.of("l2Code", code, "isNew", flag, "rows", rows.size()));
                }
            }
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter()
                    .writeValue(folder.resolve("summary.json").toFile(), summary);
        }
    }
}
