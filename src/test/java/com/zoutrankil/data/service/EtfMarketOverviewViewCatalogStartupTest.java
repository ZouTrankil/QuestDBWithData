package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.EtfMarketOverviewDailyViewReadRepository;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;

/** Ordinary D102 view is discoverable without creating a separate job or connecting to a database. */
class EtfMarketOverviewViewCatalogStartupTest {
    @TempDir Path temporary;

    @Test void actualCatalogRegistersReadonlyViewAndKeepsExistingSourceAndCacheJobs() throws Exception {
        Path ledger = temporary.resolve("uncreated.sqlite3");
        var application = new SpringApplication(QuestDataApplication.class,
                EtfMarketOverviewCacheCatalogStartupTest.NoConnectionConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("d101-catalog-test");
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("d102-catalog-test", Map.of(
                        "app.sync.ledger-path", ledger.toString(),
                        "app.sync.etf-market-overview-cache.expected-pid", "0",
                        "spring.sql.init.mode", "never", "spring.flyway.enabled", "false", "spring.main.banner-mode", "off"))));
        try (var context = application.run("show-sync-job-definitions")) {
            var datasets = context.getBean(DatasetRegistry.class);
            var jobs = context.getBean(SyncJobRegistry.class);
            assertEquals(EtfMarketOverviewDailyViewDataset.DEFINITION,
                    datasets.require("v_etf_market_overview_daily").definition());
            assertNotNull(context.getBean(EtfMarketOverviewDailyViewReadRepository.class));
            assertEquals(Set.of(DatasetDefinition.Capability.READ), datasets.require("v_etf_market_overview_daily").definition().capabilities());
            assertTrue(jobs.definitions().stream().noneMatch(job -> job.datasetId().equals("v_etf_market_overview_daily")));
            assertNotNull(jobs.require("data.etf_share", 3)); assertNotNull(jobs.require("data.etf_daily", 1));
            assertNotNull(jobs.require(EtfMarketOverviewDailyCacheJobService.JOB_ID, 1));
            var source = context.getBean("d101NoConnectionDataSource", DataSource.class);
            verify(source, never()).getConnection(); verify(source, never()).getConnection(anyString(), anyString());
            Path output = temporary.resolve("java-catalog-startup-20261006.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("task_id", "D102", "dataset_count", datasets.definitions().size(),
                            "job_count", jobs.definitions().size(), "definition", EtfMarketOverviewDailyViewDataset.DEFINITION,
                            "view_independent_jobs", 0, "database_connections", 0, "ledger_created", false)), StandardOpenOption.CREATE_NEW);
        }
        assertFalse(Files.exists(ledger)); assertFalse(Files.exists(Path.of(ledger + "-wal")));
    }
}
