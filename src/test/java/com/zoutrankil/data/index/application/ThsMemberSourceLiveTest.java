package com.zoutrankil.data.index.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE", matches="1")
class ThsMemberSourceLiveTest {
    @Test void oneBoardIsBoundedMappedAndReceiptedWithoutQuestDbWrite() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            Path folder = Path.of("artifacts/java-migration/D006", "source-" + UUID.randomUUID());
            var observed = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var page = new ThsMemberSource(context.getBean(TusharePageService.class), folder)
                    .fetchBoard("885800.TI", observed, () -> false);
            assertTrue(page.rows().size() > 0 && page.rows().size() < 10000);
            assertEquals(page.rows().size(), page.rows().stream().map(row -> row.key()).distinct().count());
            assertTrue(page.rows().stream().allMatch(row -> row.boardCode().equals("885800.TI")
                    && row.observedAt().equals(observed)));
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("source-review.json").toFile(),
                    Map.of("board", "885800.TI", "rows", page.rows().size(),
                            "sourceFingerprint", page.sourceFingerprint(), "receipt", page.responseEvidence(),
                            "observedAt", observed, "questdbWrites", 0));
        }
    }
}
