package com.zoutrankil.data.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.index.application.DcIndexJobService;
import com.zoutrankil.data.index.application.IndexCatalogJobService;
import com.zoutrankil.data.index.application.IndexDailyBasicJobService;
import com.zoutrankil.data.index.application.IndexDailyMarketJobService;
import com.zoutrankil.data.index.application.IndexMembershipJobService;
import com.zoutrankil.data.index.application.IndexMonthlyJobService;
import com.zoutrankil.data.index.application.IndexWeightJobService;
import com.zoutrankil.data.index.application.IndexWeightNameResolver;
import com.zoutrankil.data.index.application.ThsIndexJobService;
import com.zoutrankil.data.index.application.ThsMemberJobService;
import com.zoutrankil.data.index.port.DcIndexTarget;
import com.zoutrankil.data.index.port.IndexCatalogTarget;
import com.zoutrankil.data.index.port.IndexDailyBasicTarget;
import com.zoutrankil.data.index.port.IndexDailyMarketTarget;
import com.zoutrankil.data.index.port.IndexMembershipTarget;
import com.zoutrankil.data.index.port.IndexMonthlyTarget;
import com.zoutrankil.data.index.port.IndexWeightTarget;
import com.zoutrankil.data.index.port.ThsIndexTarget;
import com.zoutrankil.data.index.port.ThsMemberTarget;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.stock.port.StockDetailNameReadPort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

class IndexApplicationWiringTest {
    @TempDir Path temp;

    @Test
    void nineIndexOwnersAndCrossFamilyReadsAssembleWithoutOpeningStorage() throws Exception {
        var dataSource = mock(DataSource.class);
        Path ledger = temp.resolve("must-not-exist.sqlite");
        var application = new SpringApplication(QuestDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(
                context -> {
                    context.getBeanFactory().registerSingleton("dataSource", dataSource);
                    context.getEnvironment()
                            .getPropertySources()
                            .addFirst(
                                    new MapPropertySource(
                                            "index-wiring",
                                            Map.ofEntries(
                                                    Map.entry(
                                                            "app.sync.ledger-path",
                                                            ledger.toString()),
                                                    Map.entry("spring.main.banner-mode", "off"),
                                                    Map.entry(
                                                            "app.sync.index-catalog-table",
                                                            "java_index_wiring"),
                                                    Map.entry(
                                                            "app.sync.index-member-table",
                                                            "java_index_member_wiring"),
                                                    Map.entry(
                                                            "app.sync.ths-index-table",
                                                            "java_ths_index_wiring"),
                                                    Map.entry(
                                                            "app.sync.ths-member-table",
                                                            "java_ths_member_wiring"),
                                                    Map.entry(
                                                            "app.sync.index-daily-market-table",
                                                            "java_d019_index_daily_market_wiring"),
                                                    Map.entry(
                                                            "app.sync.index-daily-basic-table",
                                                            "java_d020_index_daily_basic_wiring"),
                                                    Map.entry(
                                                            "app.sync.index-weight-table",
                                                            "java_d021_index_weight_wiring"),
                                                    Map.entry(
                                                            "app.sync.index-monthly-table",
                                                            "java_d022_index_monthly_wiring"),
                                                    Map.entry(
                                                            "app.sync.dc-index-table",
                                                            "java_d023_dc_index_wiring"))));
                });
        try (var context =
                application.run(
                        "plan-sync-job",
                        "--job",
                        "data.ths_index",
                        "--version",
                        "1",
                        "--logical-date",
                        "2026-09-28",
                        "--from",
                        "2026-09-28",
                        "--to",
                        "2026-09-28",
                        "--parameters",
                        "{}")) {
            for (var type :
                    List.of(
                            IndexCatalogTarget.class,
                            IndexMembershipTarget.class,
                            ThsIndexTarget.class,
                            ThsMemberTarget.class,
                            IndexDailyMarketTarget.class,
                            IndexDailyBasicTarget.class,
                            IndexWeightTarget.class,
                            IndexMonthlyTarget.class,
                            DcIndexTarget.class,
                            StockDetailNameReadPort.class,
                            ExchangeCalendarReadPort.class)) {
                assertEquals(1, context.getBeansOfType(type).size(), type.getName());
            }
            for (var type :
                    List.of(
                            IndexCatalogJobService.class,
                            IndexMembershipJobService.class,
                            ThsIndexJobService.class,
                            ThsMemberJobService.class,
                            IndexDailyMarketJobService.class,
                            IndexDailyBasicJobService.class,
                            IndexWeightJobService.class,
                            IndexMonthlyJobService.class,
                            DcIndexJobService.class,
                            IndexWeightNameResolver.class)) {
                assertEquals(1, context.getBeansOfType(type).size(), type.getName());
            }
            var owners = context.getBeansOfType(SyncJobOwner.class).values();
            for (var dataset :
                    List.of(
                            "index",
                            "index_member",
                            "ths_index",
                            "ths_member",
                            "index_daily_market",
                            "index_daily_basic",
                            "index_weight",
                            "index_monthly",
                            "dc_index")) {
                assertEquals(
                        1,
                        owners.stream().filter(owner -> owner.datasetId().equals(dataset)).count(),
                        dataset);
            }
            assertEquals(
                    "java_index_wiring", context.getBean(IndexCatalogTarget.class).tableName());
            assertEquals(
                    "java_index_member_wiring",
                    context.getBean(IndexMembershipTarget.class).tableName());
            assertEquals(
                    "java_ths_index_wiring", context.getBean(ThsIndexTarget.class).tableName());
            assertEquals(
                    "java_ths_member_wiring", context.getBean(ThsMemberTarget.class).tableName());
            assertEquals(
                    "java_d019_index_daily_market_wiring",
                    context.getBean(IndexDailyMarketTarget.class).tableName());
            assertEquals(
                    "java_d020_index_daily_basic_wiring",
                    context.getBean(IndexDailyBasicTarget.class).tableName());
            assertEquals(
                    "java_d021_index_weight_wiring",
                    context.getBean(IndexWeightTarget.class).tableName());
            assertEquals(
                    "java_d022_index_monthly_wiring",
                    context.getBean(IndexMonthlyTarget.class).tableName());
            assertEquals(
                    "java_d023_dc_index_wiring", context.getBean(DcIndexTarget.class).tableName());
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
            assertFalse(context.getBeanFactory().containsSingleton("questDbClient"));
            verify(dataSource, never()).getConnection();
            verify(dataSource, never()).getConnection(anyString(), anyString());
            assertFalse(Files.exists(ledger));
        }
        assertFalse(Files.exists(ledger));
    }
}
