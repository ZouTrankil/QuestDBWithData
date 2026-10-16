package com.zoutrankil.data.config;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.ReadGroupRequest;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.ReadGroupJson;
import com.zoutrankil.data.service.ReadGroupReader;
import com.zoutrankil.data.service.SyncJobRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The public catalog and request boundary reject retired caches before accessing QuestDB. */
class CacheDatasetRetirementTest {
    static final Set<String> RETIRED = Set.of("backtest_daily","v_backtest_daily","backtest_daily_cache", "backtest_daily_cache_coverage",
            "market_breadth_daily_cache", "retail_sentiment_daily_cache",
            "etf_market_overview_daily_cache", "market_barometer_cache_coverage");
    @TempDir Path temp;

    @Test void nativeReadersRemainWhileAllRetiredCacheRequestsCannotReachStorage() throws Exception {
        var dataSource = mock(DataSource.class);
        Path ledger = temp.resolve("uncreated.sqlite3");
        var application = new SpringApplication(QuestDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("dataSource", dataSource);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("cache-retirement",
                    Map.of("app.sync.ledger-path", ledger.toString(), "app.web.enabled", "false",
                            "app.sync.batch.enabled", "false", "spring.main.banner-mode", "off")));
        });
        try (var context = application.run("list-sync-jobs")) {
            var datasets = context.getBean(DatasetRegistry.class);
            var catalog = context.getBean(ReadBindingCatalog.class);
            var jobs = context.getBean(SyncJobRegistry.class);
            var reader = context.getBean(ReadGroupReader.class);
            var json = new ReadGroupJson(datasets, context.getBean(QuestDbBoundedReader.class));
            for (String id : RETIRED) {
                assertThrows(IllegalArgumentException.class, () -> datasets.require(id), id);
                assertTrue(catalog.registrations().stream().noneMatch(binding -> binding.datasetId().equals(id)), id);
                assertTrue(jobs.definitions().stream().noneMatch(job -> job.datasetId().equals(id)), id);
                String request = """
                        {"timeoutMillis":1000,"members":[{"memberId":"retired","datasetId":"%s",
                         "definitionVersion":1,"query":{"columns":["trade_date"],"pageSize":1}}]}
                        """.formatted(id);
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> json.parse(request.getBytes(StandardCharsets.UTF_8)), id);
                assertEquals("Unknown dataset: " + id, failure.getMessage());
            }
            var query = new DatasetReadQuery(List.of("trade_date"), Map.of(), null, null, null, 1, null);
            var request = new ReadGroupRequest(RETIRED.stream().sorted()
                    .map(id -> new ReadGroupRequest.Member(id, id, 1, query)).toList(), Duration.ofSeconds(10));
            var result = reader.read(request, () -> false);
            assertFalse(result.complete());
            assertTrue(result.members().stream().allMatch(member ->
                    member.status() == ReadGroupReader.Status.FAILED && member.page() == null));

            var retained = Map.of(
                    "mv_backtest_daily", DatasetDefinition.ObjectKind.MATERIALIZED_VIEW,
                    "mv_market_breadth_daily_v1", DatasetDefinition.ObjectKind.MATERIALIZED_VIEW,
                    "v_market_breadth_daily", DatasetDefinition.ObjectKind.VIEW,
                    "mv_retail_sentiment_daily_v1", DatasetDefinition.ObjectKind.MATERIALIZED_VIEW,
                    "v_retail_sentiment_daily", DatasetDefinition.ObjectKind.VIEW,
                    "v_etf_market_overview_daily", DatasetDefinition.ObjectKind.VIEW,
                    "cn_bond_yield_curve", DatasetDefinition.ObjectKind.TABLE);
            var bindings = catalog.bind(datasets);
            for (var entry : retained.entrySet()) {
                assertEquals(entry.getValue(), datasets.require(entry.getKey()).definition().objectKind());
                assertTrue(bindings.stream().anyMatch(binding ->
                        binding.definition().datasetId().equals(entry.getKey())), entry.getKey());
            }
            verifyNoInteractions(dataSource);
            assertFalse(Files.exists(ledger));
        }
    }
}
