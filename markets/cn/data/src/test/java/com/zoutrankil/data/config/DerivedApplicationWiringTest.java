package com.zoutrankil.data.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.derived.application.EquityStyleMonthlyJobService;
import com.zoutrankil.data.derived.application.MacroCoreMonthlyJobService;
import com.zoutrankil.data.derived.application.MarketBreadthDailyJobService;
import com.zoutrankil.data.derived.application.RetailSentimentDailyJobService;
import com.zoutrankil.data.derived.port.EquityStyleMonthlySourceReadPort;
import com.zoutrankil.data.derived.port.EquityStyleMonthlyTarget;
import com.zoutrankil.data.derived.port.MacroCoreMonthlySourceReadPort;
import com.zoutrankil.data.derived.port.MacroCoreMonthlyTarget;
import com.zoutrankil.data.derived.port.MarketBreadthDailyTarget;
import com.zoutrankil.data.derived.port.RetailSentimentDailyTarget;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

class DerivedApplicationWiringTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownersAndPortsAssembleWithOriginalDefaultsOrOverridesWithoutOpeningStorage(
            boolean override) throws Exception {
        var dataSource = mock(DataSource.class);
        Path ledger = temp.resolve("must-not-exist.sqlite3");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("app.sync.ledger-path", ledger.toString());
        properties.put("spring.main.banner-mode", "off");
        if (override) {
            properties.put(
                    "app.sync.equity-style-monthly.source-table", "java_d103_index_monthly_wiring");
            properties.put(
                    "app.sync.equity-style-monthly.target-table",
                    "java_d103_equity_style_monthly_wiring");
            properties.put(
                    "app.sync.macro-core-monthly.target-table",
                    "java_d104_macro_core_monthly_wiring");
            properties.put(
                    "app.sync.market-breadth-daily.expected-target-id",
                    "deferred-wiring-validation");
            properties.put(
                    "app.sync.retail-sentiment-daily.expected-target-id",
                    "deferred-wiring-validation");
        }
        var application = new SpringApplication(QuestDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(
                context -> {
                    context.getBeanFactory().registerSingleton("dataSource", dataSource);
                    context.getEnvironment()
                            .getPropertySources()
                            .addFirst(new MapPropertySource("derived-wiring", properties));
                });
        try (var context = application.run("list-sync-jobs")) {
            for (var type :
                    List.of(
                            EquityStyleMonthlySourceReadPort.class,
                            EquityStyleMonthlyTarget.class,
                            MacroCoreMonthlySourceReadPort.class,
                            MacroCoreMonthlyTarget.class,
                            MarketBreadthDailyTarget.class,
                            RetailSentimentDailyTarget.class,
                            EquityStyleMonthlyJobService.class,
                            MacroCoreMonthlyJobService.class,
                            MarketBreadthDailyJobService.class,
                            RetailSentimentDailyJobService.class)) {
                assertEquals(1, context.getBeansOfType(type).size(), type.getName());
            }
            assertEquals(
                    override ? "java_d103_index_monthly_wiring" : "index_monthly",
                    context.getBean(EquityStyleMonthlySourceReadPort.class).table());
            assertEquals(
                    override ? "java_d103_equity_style_monthly_wiring" : "",
                    context.getBean(EquityStyleMonthlyTarget.class).table());
            assertEquals(
                    override ? "java_d104_macro_core_monthly_wiring" : "",
                    context.getBean(MacroCoreMonthlyTarget.class).table());
            var owners = context.getBeansOfType(SyncJobOwner.class).values();
            for (var dataset :
                    List.of(
                            "equity_style_monthly",
                            "macro_core_monthly",
                            "mv_market_breadth_daily",
                            "mv_retail_sentiment_daily")) {
                assertEquals(
                        1,
                        owners.stream().filter(owner -> owner.datasetId().equals(dataset)).count(),
                        dataset);
            }
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
            assertFalse(Files.exists(ledger));
            verifyNoInteractions(dataSource);
        }
    }
}
