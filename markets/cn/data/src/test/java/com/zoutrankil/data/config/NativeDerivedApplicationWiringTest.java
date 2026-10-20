package com.zoutrankil.data.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.derived.application.MarketSentimentDailyJobService;
import com.zoutrankil.data.derived.application.RegimeFeaturesMonitorDailyJobService;
import com.zoutrankil.data.derived.port.MarketSentimentDailySourceReadPort;
import com.zoutrankil.data.derived.port.MarketSentimentDailyTarget;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.SyncJobRegistry;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

class NativeDerivedApplicationWiringTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredNativeOwnersRetainTargetsBudgetsAndNoIoAssembly(boolean override)
            throws Exception {
        var source = mock(DataSource.class);
        Path ledger = temporary.resolve("uncreated.sqlite3");
        String market =
                override ? "java_d121_market_sentiment_daily_wiring" : "market_sentiment_daily";
        String regime =
                override
                        ? "java_regime_features_monitor_daily_wiring"
                        : "regime_features_monitor_daily";
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("app.sync.ledger-path", ledger.toString());
        properties.put("spring.main.banner-mode", "off");
        properties.put("app.sync.market-sentiment-table", market);
        properties.put("app.sync.regime-monitor-table", regime);
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(
                context -> {
                    context.getBeanFactory().registerSingleton("dataSource", source);
                    context.getEnvironment()
                            .getPropertySources()
                            .addFirst(new MapPropertySource("native-derived-wiring", properties));
                });
        try (var context = app.run("list-sync-jobs")) {
            assertEquals(
                    1, context.getBeansOfType(MarketSentimentDailySourceReadPort.class).size());
            assertEquals(1, context.getBeansOfType(MarketSentimentDailyTarget.class).size());
            assertSame(
                    context.getBean(MarketSentimentDailySourceReadPort.class),
                    context.getBean(MarketSentimentDailyTarget.class));
            assertEquals(market, context.getBean(MarketSentimentDailyTarget.class).table());
            var marketOwner = context.getBean(MarketSentimentDailyJobService.class);
            var regimeOwner = context.getBean(RegimeFeaturesMonitorDailyJobService.class);
            assertEquals(market, marketOwner.definition().objectName());
            assertEquals(regime, regimeOwner.definition().objectName());
            assertEquals(53, marketOwner.definition().columns().size());
            assertEquals(21, regimeOwner.definition().columns().size());
            assertEquals(
                    Set.of(SyncJobDefinition.Mode.MATERIALIZE), marketOwner.supportedSyncModes());
            assertEquals(
                    Set.of(SyncJobDefinition.Mode.MATERIALIZE), regimeOwner.supportedSyncModes());
            var jobs = context.getBean(SyncJobRegistry.class);
            assertEquals(
                    MarketSentimentDailyJobService.jobDefinition(),
                    jobs.require(MarketSentimentDailyJobService.JOB_ID, 4));
            assertEquals(
                    RegimeFeaturesMonitorDailyJobService.jobDefinition(),
                    jobs.require(RegimeFeaturesMonitorDailyJobService.JOB_ID, 4));
            assertTrue(regimeOwner.definition().dependencies().contains("cn_bond_yield_curve"));
            assertTrue(
                    RegimeFeaturesMonitorDailyJobService.SOURCES.contains("cn_bond_yield_curve"));
            var datasets = context.getBean(DatasetRegistry.class);
            assertEquals(
                    55,
                    datasets.definitions().stream()
                            .filter(
                                    value ->
                                            value.capabilities()
                                                    .contains(DatasetDefinition.Capability.READ))
                            .count());
            assertEquals(55, context.getBean(ReadBindingCatalog.class).bind(datasets).size());
            // 五项公告/利率只增加读取契约，不接管来源写入。
            for (var id : Set.of("stk_shock", "stk_high_shock", "shibor", "hibor", "shibor_lpr"))
                assertEquals(
                        Set.of(DatasetDefinition.Capability.READ),
                        datasets.require(id).definition().capabilities());
            verifyNoInteractions(source);
            assertFalse(Files.exists(ledger));
        }
    }
}
