package com.zoutrankil.data.config;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.derived.application.*;
import com.zoutrankil.data.derived.port.*;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.SyncJobOwner;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DerivedApplicationWiringTest {
    @TempDir Path temp;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ownersAndPortsAssembleWithOriginalDefaultsOrOverridesWithoutOpeningStorage(boolean override) throws Exception {
        var dataSource = mock(DataSource.class);
        Path ledger = temp.resolve("must-not-exist.sqlite3");
        Map<String,Object> properties = new LinkedHashMap<>();
        properties.put("app.sync.ledger-path", ledger.toString());
        properties.put("spring.main.banner-mode", "off");
        if (override) {
            properties.put("app.sync.equity-style-monthly.source-table", "java_d103_index_monthly_wiring");
            properties.put("app.sync.equity-style-monthly.target-table", "java_d103_equity_style_monthly_wiring");
            properties.put("app.sync.macro-core-monthly.target-table", "java_d104_macro_core_monthly_wiring");
            properties.put("app.sync.market-breadth-daily.expected-target-id", "deferred-wiring-validation");
            properties.put("app.sync.retail-sentiment-daily.expected-target-id", "deferred-wiring-validation");
        }
        var application = new SpringApplication(QuestDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("dataSource", dataSource);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("derived-wiring", properties));
        });
        try (var context = application.run("list-sync-jobs")) {
            for (var type : List.of(EquityStyleMonthlySourceReadPort.class, EquityStyleMonthlyTarget.class,
                    MacroCoreMonthlySourceReadPort.class, MacroCoreMonthlyTarget.class,
                    MarketBreadthDailyV1Target.class, RetailSentimentDailyV1Target.class,
                    EtfMarketOverviewSource.class, EtfMarketOverviewPublisher.class,
                    EtfMarketOverviewPublicationTarget.class, EtfMarketOverviewSessions.class,
                    EquityStyleMonthlyJobService.class, MacroCoreMonthlyJobService.class,
                    MarketBreadthDailyV1JobService.class, RetailSentimentDailyV1JobService.class,
                    EtfMarketOverviewDailyCacheJobService.class)) {
                assertEquals(1, context.getBeansOfType(type).size(), type.getName());
            }
            assertSame(context.getBean(EtfMarketOverviewSource.class), context.getBean(EtfMarketOverviewPublisher.class));
            assertEquals(override ? "java_d103_index_monthly_wiring" : "index_monthly",
                    context.getBean(EquityStyleMonthlySourceReadPort.class).table());
            assertEquals(override ? "java_d103_equity_style_monthly_wiring" : "",
                    context.getBean(EquityStyleMonthlyTarget.class).table());
            assertEquals(override ? "java_d104_macro_core_monthly_wiring" : "",
                    context.getBean(MacroCoreMonthlyTarget.class).table());
            var owners = context.getBeansOfType(SyncJobOwner.class).values();
            for (var dataset : List.of("equity_style_monthly", "macro_core_monthly", "mv_market_breadth_daily_v1",
                    "mv_retail_sentiment_daily_v1", "etf_market_overview_daily_cache")) {
                assertEquals(1, owners.stream().filter(owner -> owner.datasetId().equals(dataset)).count(), dataset);
            }
            var datasets = context.getBean(DatasetRegistry.class);
            assertEquals(56, datasets.definitions().stream()
                    .filter(value -> value.capabilities().contains(DatasetDefinition.Capability.READ)).count());
            assertEquals(56, context.getBean(ReadBindingCatalog.class).bind(datasets).size());
            assertFalse(Files.exists(ledger));
            verifyNoInteractions(dataSource);
        }
    }
}
