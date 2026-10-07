package com.zoutrankil.data.config;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;
import com.zoutrankil.data.calendar.port.ExchangeCalendarTarget;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.SyncJobOwner;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.stock.application.*;
import com.zoutrankil.data.stock.port.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockCalendarApplicationWiringTest {
    @TempDir Path temp;

    @Test void completedStockAndCalendarTargetsAssembleWithoutOpeningStorage() throws Exception {
        var dataSource = mock(DataSource.class);
        Path ledger = temp.resolve("must-not-exist.sqlite");
        var application = new SpringApplication(QuestDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("dataSource", dataSource);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("stock-calendar-wiring",
                    Map.of("app.sync.ledger-path", ledger.toString(), "spring.main.banner-mode", "off")));
        });
        try (var context = application.run("plan-sync-job", "--job", "data.stock_basic", "--version", "2",
                "--logical-date", "2026-09-28", "--parameters", "{\"codes\":[\"000001.SZ\"]}")) {
            // Suspend still uses its legacy adapter until the remaining T15 vertical is complete.
            for (var type : List.of(DailyTarget.class, DailyBasicTarget.class, StockBasicTarget.class,
                    ExchangeCalendarTarget.class, StockFactorTarget.class, StockLimitTarget.class,
                    StockStDailyTarget.class, StockDetailTarget.class)) {
                assertEquals(1, context.getBeansOfType(type).size(), type.getName());
            }
            for (var type : List.of(DailyJobService.class, DailyBasicJobService.class, StockBasicJobService.class,
                    ExchangeCalendarJobService.class, StockFactorJobService.class, StockLimitJobService.class,
                    StockStDailyJobService.class, StockDetailInfoJobService.class, StockSuspendJobService.class,
                    StockBasicWriteService.class)) {
                assertEquals(1, context.getBeansOfType(type).size(), type.getName());
            }
            var owners = context.getBeansOfType(SyncJobOwner.class).values();
            for (var dataset : List.of("daily", "daily_basic", "stock_basic_snapshot", "exchange_calendar",
                    "stk_factor", "stk_limit", "stk_st_daily", "stock_detail_info", "stk_suspend")) {
                assertEquals(1, owners.stream().filter(owner -> owner.datasetId().equals(dataset)).count(), dataset);
            }
            var datasets = context.getBean(DatasetRegistry.class);
            assertEquals(56, datasets.definitions().stream()
                    .filter(value -> value.capabilities().contains(DatasetDefinition.Capability.READ)).count());
            assertEquals(56, context.getBean(ReadBindingCatalog.class).bind(datasets).size());
            assertFalse(context.getBeanFactory().containsSingleton("questDbClient"));
            verify(dataSource, never()).getConnection();
            verify(dataSource, never()).getConnection(anyString(), anyString());
            assertFalse(Files.exists(ledger));
        }
        assertFalse(Files.exists(ledger));
    }
}
