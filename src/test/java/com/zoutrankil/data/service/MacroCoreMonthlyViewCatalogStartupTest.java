package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.MacroCoreMonthlyViewReadRepository;
import java.nio.file.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** D105 registers its read interface and reuses the single D104 owner without startup IO. */
class MacroCoreMonthlyViewCatalogStartupTest {
    @TempDir Path temporary;

    @Test void ordinaryMonthlyViewUsesExistingCanonicalBaseOwnerWithoutStartupIo() throws Exception {
        Path ledger = temporary.resolve("uncreated.sqlite3");
        var application = new SpringApplication(QuestDataApplication.class,
                EtfMarketOverviewCacheCatalogStartupTest.NoConnectionConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("d101-catalog-test");
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("d105-catalog-test", Map.of(
                        "app.sync.ledger-path", ledger.toString(),
                        "app.sync.etf-market-overview-cache.expected-pid", "0",
                        "spring.sql.init.mode", "never", "spring.flyway.enabled", "false",
                        "spring.main.banner-mode", "off"))));
        try (var context = application.run("show-sync-job-definitions")) {
            var datasets = context.getBean(DatasetRegistry.class);
            var jobs = context.getBean(SyncJobRegistry.class);
            var definition = datasets.require("v_macro_core_monthly").definition();
            assertEquals(MacroCoreMonthlyViewDataset.DEFINITION, definition);
            assertEquals(Set.of(DatasetDefinition.Capability.READ), definition.capabilities());
            assertEquals(List.of("macro_core_monthly"), definition.dependencies());
            assertNotNull(context.getBean(MacroCoreMonthlyViewReadRepository.class));
            assertEquals(MacroCoreMonthlyJobService.definition(), jobs.require(
                    MacroCoreMonthlyViewDataset.BASE_REFRESH_JOB_ID,
                    MacroCoreMonthlyViewDataset.BASE_REFRESH_JOB_VERSION));
            assertEquals(1, jobs.definitions().stream().filter(job -> job.datasetId().equals("macro_core_monthly")).count());
            assertTrue(jobs.definitions().stream().noneMatch(job -> job.datasetId().equals("v_macro_core_monthly")));
            assertEquals(56, datasets.definitions().size());
            assertEquals(44, jobs.definitions().size());
            var source = context.getBean("d101NoConnectionDataSource", DataSource.class);
            verify(source, never()).getConnection();
            verify(source, never()).getConnection(anyString(), anyString());
            Path output = temporary.resolve("java-catalog-startup-20261007.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("task_id", "D105", "dataset_count", datasets.definitions().size(),
                            "job_count", jobs.definitions().size(), "definition", definition,
                            "base_refresh_job", MacroCoreMonthlyJobService.definition(), "view_independent_jobs", 0,
                            "database_connections", 0, "ledger_created", false)), StandardOpenOption.CREATE_NEW);
        }
        assertFalse(Files.exists(ledger));
        assertFalse(Files.exists(Path.of(ledger + "-wal")));
    }
}
