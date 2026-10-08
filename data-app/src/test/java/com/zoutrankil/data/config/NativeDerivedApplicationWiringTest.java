package com.zoutrankil.data.config;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.derived.application.*;
import com.zoutrankil.data.derived.port.*;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.SyncJobRegistry;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeDerivedApplicationWiringTest {
    @TempDir Path temporary;
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void configuredNativeOwnersRetainTargetsBudgetsAndNoIoAssembly(boolean override) throws Exception {
        var source = mock(DataSource.class);
        Path ledger = temporary.resolve("uncreated.sqlite3");
        String market = override ? "java_d121_market_sentiment_daily_wiring" : "market_sentiment_daily";
        String regime = override ? "java_regime_features_monitor_daily_wiring" : "regime_features_monitor_daily";
        Map<String,Object> properties = new LinkedHashMap<>();
        properties.put("app.sync.ledger-path", ledger.toString());
        properties.put("spring.main.banner-mode", "off");
        properties.put("app.sync.market-sentiment-table", market);
        properties.put("app.sync.regime-monitor-table", regime);
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("dataSource", source);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("native-derived-wiring", properties));
        });
        try (var context = app.run("list-sync-jobs")) {
            assertEquals(1, context.getBeansOfType(MarketSentimentDailySourceReadPort.class).size());
            assertEquals(1, context.getBeansOfType(MarketSentimentDailyTarget.class).size());
            assertSame(context.getBean(MarketSentimentDailySourceReadPort.class), context.getBean(MarketSentimentDailyTarget.class));
            assertEquals(market, context.getBean(MarketSentimentDailyTarget.class).table());
            var marketOwner = context.getBean(MarketSentimentDailyJobService.class);
            var regimeOwner = context.getBean(RegimeFeaturesMonitorDailyJobService.class);
            assertEquals(market, marketOwner.definition().objectName());
            assertEquals(regime, regimeOwner.definition().objectName());
            assertEquals(53, marketOwner.definition().columns().size());
            assertEquals(21, regimeOwner.definition().columns().size());
            assertEquals(Set.of(SyncJobDefinition.Mode.MATERIALIZE), marketOwner.supportedSyncModes());
            assertEquals(Set.of(SyncJobDefinition.Mode.MATERIALIZE), regimeOwner.supportedSyncModes());
            var jobs = context.getBean(SyncJobRegistry.class);
            assertEquals(MarketSentimentDailyJobService.jobDefinition(), jobs.require(MarketSentimentDailyJobService.JOB_ID, 2));
            assertEquals(RegimeFeaturesMonitorDailyJobService.jobDefinition(), jobs.require(RegimeFeaturesMonitorDailyJobService.JOB_ID, 2));
            assertFalse(regimeOwner.definition().dependencies().contains("cn_bond_yield_curve"));
            assertTrue(RegimeFeaturesMonitorDailyJobService.SOURCES.contains("cn_bond_yield_curve"));
            var datasets = context.getBean(DatasetRegistry.class);
            assertEquals(56, datasets.definitions().stream().filter(value -> value.capabilities().contains(DatasetDefinition.Capability.READ)).count());
            assertEquals(56, context.getBean(ReadBindingCatalog.class).bind(datasets).size());
            verifyNoInteractions(source);
            assertFalse(Files.exists(ledger));
        }
    }
}
