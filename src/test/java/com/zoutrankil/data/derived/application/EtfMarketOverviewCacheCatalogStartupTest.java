package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.EtfMarketOverviewDailyCacheDataset;
import com.zoutrankil.data.domain.MarketBarometerCacheCoverageDataset;
import com.zoutrankil.data.domain.SyncJobDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The actual finite catalog command resolves D101 with no database or configured owner process. */
class EtfMarketOverviewCacheCatalogStartupTest {
    @TempDir Path temp;

    @Test void actualCatalogStartupResolvesD101AndRefusesUnconfiguredOwnerBeforeAnyConnection() throws Exception {
        Path ledger = temp.resolve("uncreated-ledger.sqlite3");
        Path ownerArtifacts = temp.resolve("uncreated-owner-artifacts");
        var application = new SpringApplication(QuestDataApplication.class, NoConnectionConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("d101-catalog-test");
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("d101-catalog-test", Map.of(
                        "app.sync.ledger-path", ledger.toString(),
                        "app.sync.etf-market-overview-cache.expected-pid", "0",
                        "app.sync.etf-market-overview-cache.python-executable", "",
                        "app.sync.etf-market-overview-cache.artifact-root", ownerArtifacts.toString(),
                        "spring.sql.init.mode", "never",
                        "spring.flyway.enabled", "false",
                        "spring.main.banner-mode", "off"))));
        try (var context = application.run("show-sync-job-definitions")) {
            var datasets = context.getBean(DatasetRegistry.class);
            var registry = context.getBean(SyncJobRegistry.class);
            var owner = context.getBean(EtfMarketOverviewDailyCacheJobService.class);
            var job = registry.require(EtfMarketOverviewDailyCacheJobService.JOB_ID, 1);
            assertEquals(EtfMarketOverviewDailyCacheJobService.definition(), job);
            assertEquals(owner.datasetId(), job.datasetId());
            assertEquals(SyncJobDefinition.Mode.INCREMENTAL, job.defaultMode());
            assertEquals(Set.of(SyncJobDefinition.Mode.INCREMENTAL, SyncJobDefinition.Mode.MATERIALIZE,
                    SyncJobDefinition.Mode.RECONCILE), job.supportedModes());
            assertEquals(SyncJobDefinition.Frequency.MANUAL, job.frequency());
            assertTrue(job.enabled()); assertFalse(job.dailyEligible());
            assertFalse(registry.dailyJobs().contains(job));
            assertEquals(java.util.List.of(
                    new SyncJobDefinition.JobRef("data.etf_share", 3),
                    new SyncJobDefinition.JobRef("data.etf_daily", 1),
                    new SyncJobDefinition.JobRef("data.etf_basic", 1)), job.dependencies());
            for (var dependency : job.dependencies()) assertNotNull(registry.require(dependency.jobId(), dependency.version()));
            var cache = datasets.require(job.datasetId()).definition();
            assertEquals(EtfMarketOverviewDailyCacheDataset.DEFINITION, cache);
            assertEquals(Set.of(DatasetDefinition.Capability.READ, DatasetDefinition.Capability.WRITE), cache.capabilities());
            assertEquals("python.MarketBarometerReadThroughCache.read", cache.owner());
            assertEquals(5, cache.columns().size());
            assertEquals(Set.of(DatasetDefinition.Capability.READ),
                    datasets.require(MarketBarometerCacheCoverageDataset.DEFINITION.datasetId()).definition().capabilities());
            var gateway = context.getBean(EtfMarketOverviewCacheOwnerGateway.class);
            assertEquals(0, gateway.config().expectedPid());
            var day = LocalDate.of(2026, 9, 17);
            assertThrows(IllegalStateException.class, () -> gateway.preview(day));
            assertThrows(IllegalStateException.class, () -> owner.plan(day, day, day.plusDays(1), null));
            assertThrows(IllegalStateException.class, owner::targetId);
            assertThrows(IllegalStateException.class, owner::delegatedPort);
            var source = context.getBean("d101NoConnectionDataSource", DataSource.class);
            assertSame(source, context.getBean(JdbcTemplate.class).getDataSource());
            verify(source, never()).getConnection(); verify(source, never()).getConnection(anyString(), anyString());
        }
        assertFalse(Files.exists(ledger)); assertFalse(Files.exists(Path.of(ledger + "-wal")));
        assertFalse(Files.exists(ownerArtifacts));
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("d101-catalog-test")
    static class NoConnectionConfiguration {
        @Bean DataSource d101NoConnectionDataSource() throws Exception {
            var source = mock(DataSource.class);
            doThrow(new AssertionError("Catalog startup must not request a database connection")).when(source).getConnection();
            doThrow(new AssertionError("Catalog startup must not request a database connection")).when(source).getConnection(anyString(), anyString());
            return source;
        }
    }
}
