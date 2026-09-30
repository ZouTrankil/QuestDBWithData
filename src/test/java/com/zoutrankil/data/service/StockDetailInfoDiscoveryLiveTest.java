package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
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
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE", matches="1")
class StockDetailInfoDiscoveryLiveTest {
    @Test void allStatusExchangeSlicesReturnUniqueBoundedMasterRows() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var folder = Path.of("artifacts/java-migration/D002", "discovery-" + UUID.randomUUID());
            var source = new StockDetailInfoDiscovery(context.getBean(TusharePageService.class), folder);
            var result = source.fetch(Instant.now().truncatedTo(ChronoUnit.MICROS), () -> false);
            assertTrue(result.rows().size() > 0);
            assertEquals(9, result.sliceCounts().size());
            assertEquals(9, result.receipts().size());
            assertEquals(result.rows().size(), result.rows().stream().map(r -> r.tsCode()).distinct().count());
            assertTrue(result.sliceCounts().values().stream().allMatch(n -> n < 6000));
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(
                    folder.resolve("discovery-validation.json").toFile(),
                    Map.of("requestSlices",9,"uniqueRows",result.rows().size(),
                            "sliceCounts",result.sliceCounts(),"receipts",result.receipts(),"writes",0));
        }
    }
}
