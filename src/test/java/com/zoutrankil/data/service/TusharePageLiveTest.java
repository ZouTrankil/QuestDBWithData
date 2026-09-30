package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.domain.temporal.TemporalValues;
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

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "TUSHARE_PAGE_LIVE", matches = "1")
class TusharePageLiveTest {
    @Test void boundedExistingStockBasicSliceCompletesFromRealSource() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context -> context.addBeanFactoryPostProcessor(factory ->
                ((BeanDefinitionRegistry) factory).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var contract = new PageContract("stock_basic", TushareClient.STOCK_FIELDS,
                    List.of("ts_code"), Set.of("ts_code", "list_status"),
                    PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE,
                    null, null, 2, 2, 1, 2,
                    "stock_basic exact-code diagnostic; source cap is conservative");
            var rows = new ArrayList<Map<String, String>>();
            var result = context.getBean(TusharePageService.class).execute(contract,
                    Map.of("ts_code", "000001.SZ", "list_status", "L"), (page, receipt) -> {
                        for (var row : page.rows()) {
                            assertEquals("000001.SZ", row.get("ts_code").asText());
                            TemporalValues.businessDate(row.get("list_date").asText(), TemporalValues.DateFormat.BASIC);
                            var safe = new LinkedHashMap<String, String>();
                            for (var field : TushareClient.STOCK_FIELDS)
                                safe.put(field, row.get(field).isNull() ? null : row.get(field).asText());
                            rows.add(safe);
                        }
                    }, row -> assertEquals("000001.SZ", row.get("ts_code").asText()), () -> false);
            assertEquals(1, result.pages());
            assertEquals(1, result.rows());
            assertEquals(1, rows.size());
            Path output = Path.of("artifacts/java-migration/F006/source-page.json");
            Files.createDirectories(output.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),
                    Map.of("observed_at", Instant.now().toString(), "endpoint", contract.endpoint(),
                            "params", Map.of("ts_code", "000001.SZ", "list_status", "L"),
                            "pages", result.pages(), "rows", rows));
        }
    }
}
