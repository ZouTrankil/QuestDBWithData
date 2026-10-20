package com.zoutrankil.data.derived.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.derived.storage.MacroCoreMonthlyReadRepository;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.derived.MacroCoreMonthlyDataset;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.SyncJobRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import javax.sql.DataSource;

class MacroCoreMonthlyCatalogStartupTest {
    @TempDir Path temporary;

    @Test
    void actualCatalogAdmitsTypedMonthlyDatasetAndCanonicalJobWithoutIo() throws Exception {
        Path ledger = temporary.resolve("uncreated.sqlite3");
        // 直接装配 A 股组件时，测试也明确启用对应市场配置。
        var application =
                new SpringApplication(
                        QuestDataApplication.class,
                        CatalogNoConnectionTestSupport.NoConnectionConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("market-cn", "d101-catalog-test");
        application.addInitializers(
                context ->
                        context.getEnvironment()
                                .getPropertySources()
                                .addFirst(
                                        new MapPropertySource(
                                                "d104-catalog-test",
                                                Map.of(
                                                        "app.sync.ledger-path",
                                                        ledger.toString(),
                                                        "app.sync.etf-market-overview-cache.expected-pid",
                                                        "0",
                                                        "spring.sql.init.mode",
                                                        "never",
                                                        "spring.flyway.enabled",
                                                        "false",
                                                        "spring.main.banner-mode",
                                                        "off"))));
        try (var context = application.run("show-sync-job-definitions")) {
            var datasets = context.getBean(DatasetRegistry.class);
            var jobs = context.getBean(SyncJobRegistry.class);
            assertEquals(
                    MacroCoreMonthlyDataset.DEFINITION,
                    datasets.require("macro_core_monthly").definition());
            assertNotNull(context.getBean(MacroCoreMonthlyReadRepository.class));
            assertEquals(
                    MacroCoreMonthlyJobService.definition(),
                    jobs.require("data.macro_core_monthly", 1));
            assertEquals(
                    1,
                    jobs.definitions().stream()
                            .filter(job -> job.datasetId().equals("macro_core_monthly"))
                            .count());
            assertTrue(MacroCoreMonthlyJobService.definition().dependencies().isEmpty());
            var source = context.getBean("d101NoConnectionDataSource", DataSource.class);
            verify(source, never()).getConnection();
            verify(source, never()).getConnection(anyString(), anyString());
            Path result = temporary.resolve("java-catalog-startup-final-20261007.json");
            Files.createDirectories(result.getParent());
            Files.writeString(
                    result,
                    JobDefinitionJson.mapper()
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(
                                    Map.of(
                                            "task_id",
                                            "D104",
                                            "dataset_count",
                                            datasets.definitions().size(),
                                            "job_count",
                                            jobs.definitions().size(),
                                            "definition",
                                            MacroCoreMonthlyDataset.DEFINITION,
                                            "job",
                                            MacroCoreMonthlyJobService.definition(),
                                            "database_connections",
                                            0,
                                            "ledger_created",
                                            false)),
                    StandardOpenOption.CREATE_NEW);
        }
        assertFalse(Files.exists(ledger));
        assertFalse(Files.exists(Path.of(ledger + "-wal")));
    }
}
