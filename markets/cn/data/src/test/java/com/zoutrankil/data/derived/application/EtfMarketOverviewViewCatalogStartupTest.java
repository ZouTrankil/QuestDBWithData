package com.zoutrankil.data.derived.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.derived.storage.EtfMarketOverviewDailyViewReadRepository;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.fund.EtfMarketOverviewDailyViewDataset;
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
import java.util.Set;

import javax.sql.DataSource;

/** Ordinary D102 view is discoverable without creating a separate job or connecting to a database. */
class EtfMarketOverviewViewCatalogStartupTest {
    @TempDir Path temporary;

    @Test
    void actualCatalogRegistersReadonlyViewAndRetiresCacheJobs() throws Exception {
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
                                                "d102-catalog-test",
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
                    EtfMarketOverviewDailyViewDataset.DEFINITION,
                    datasets.require("mv_etf_market_overview_daily").definition());
            assertNotNull(context.getBean(EtfMarketOverviewDailyViewReadRepository.class));
            assertEquals(
                    Set.of(DatasetDefinition.Capability.READ),
                    datasets.require("mv_etf_market_overview_daily").definition().capabilities());
            assertTrue(
                    jobs.definitions().stream()
                            .noneMatch(
                                    job -> job.datasetId().equals("mv_etf_market_overview_daily")));
            assertNotNull(jobs.require("data.etf_share", 3));
            assertNotNull(jobs.require("data.etf_daily", 1));
            assertTrue(
                    jobs.definitions().stream()
                            .noneMatch(
                                    job ->
                                            job.datasetId()
                                                    .equals("etf_market_overview_daily_cache")));
            assertTrue(
                    datasets.definitions().stream()
                            .noneMatch(
                                    dataset ->
                                            Set.of(
                                                            "etf_market_overview_daily_cache",
                                                            "market_barometer_cache_coverage")
                                                    .contains(dataset.objectName())));
            var source = context.getBean("d101NoConnectionDataSource", DataSource.class);
            verify(source, never()).getConnection();
            verify(source, never()).getConnection(anyString(), anyString());
            Path output = temporary.resolve("java-catalog-startup-20261006.json");
            Files.createDirectories(output.getParent());
            Files.writeString(
                    output,
                    JobDefinitionJson.mapper()
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(
                                    Map.of(
                                            "task_id",
                                            "D102",
                                            "dataset_count",
                                            datasets.definitions().size(),
                                            "job_count",
                                            jobs.definitions().size(),
                                            "definition",
                                            EtfMarketOverviewDailyViewDataset.DEFINITION,
                                            "view_independent_jobs",
                                            0,
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
