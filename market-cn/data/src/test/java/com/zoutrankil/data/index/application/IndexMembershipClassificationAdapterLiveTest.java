package com.zoutrankil.data.index.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validates the actual classification adapter and frozen slice selection against Tushare. */
@EnabledIfEnvironmentVariable(named = "TUSHARE_PAGE_LIVE", matches = "1")
class IndexMembershipClassificationAdapterLiveTest {
    @Test void liveCatalogIncludesUnpublishedIndustriesAndBindsRequestedL2Names() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context -> context.addBeanFactoryPostProcessor(factory ->
                ((BeanDefinitionRegistry) factory).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            Path folder = Path.of("artifacts/java-migration/D005", "classification-adapter-" + UUID.randomUUID());
            var catalog = new IndexMembershipClassificationSource(context.getBean(TusharePageService.class), folder)
                    .fetch(() -> false);
            assertEquals(134, catalog.industries().size());
            var selected = catalog.select(List.of("801011.SI", "801217.SI"), IndexMembershipSource.Selection.BOTH);
            assertEquals("林业Ⅱ", selected.getFirst().industryName());
            assertEquals("本地生活服务Ⅱ", selected.get(1).industryName());
            assertEquals("BOTH", selected.getFirst().selection().name());
            assertEquals("0", catalog.industries().stream().filter(row -> row.indexCode().equals("801011.SI"))
                    .findFirst().orElseThrow().published());
            byte[] bytes = Files.readAllBytes(Path.of(catalog.receipt()));
            assertEquals(catalog.fingerprint(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            assertTrue(bytes.length < 1024 * 1024);
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter()
                    .writeValue(folder.resolve("adapter-review.json").toFile(), Map.of(
                            "industryCount", catalog.industries().size(), "selected", selected,
                            "classificationFingerprint", catalog.fingerprint(), "receipt", catalog.receipt(),
                            "questdbWrites", 0));
        }
    }
}
