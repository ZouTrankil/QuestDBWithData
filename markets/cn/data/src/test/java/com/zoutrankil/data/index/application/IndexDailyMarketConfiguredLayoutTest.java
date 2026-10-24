package com.zoutrankil.data.index.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.index.policy.IndexDailyMarketUniverse;
import com.zoutrankil.data.index.storage.IndexDailyMarketReadRepository;
import com.zoutrankil.data.index.storage.QuestDbIndexDailyMarketTarget;
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
import java.time.LocalDate;
import java.util.Map;

import javax.sql.DataSource;

class IndexDailyMarketConfiguredLayoutTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void oneSettingSelectsTheJobReaderTargetAndPublicBindingWithoutIo(int version)
            throws Exception {
        var source = mock(DataSource.class);
        var ledger = root.resolve("absent.sqlite3");
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(
                context -> {
                    context.getBeanFactory().registerSingleton("dataSource", source);
                    context.getEnvironment()
                            .getPropertySources()
                            .addFirst(
                                    new MapPropertySource(
                                            "layout",
                                            Map.of(
                                                    "app.sync.index-daily-market-schema-version",
                                                    version,
                                                    "app.sync.index-daily-market-table",
                                                    "java_d019_index_daily_market_configured",
                                                    "app.sync.ledger-path",
                                                    ledger.toString(),
                                                    "spring.main.banner-mode",
                                                    "off")));
                });
        try (var context = app.run("list-sync-jobs")) {
            var jobs = context.getBean(SyncJobRegistry.class);
            assertEquals(
                    IndexDailyMarketSyncJobOwner.definition(version),
                    jobs.require("data.index_daily_market", version));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> jobs.require("data.index_daily_market", 3 - version));
            var datasets = context.getBean(DatasetRegistry.class);
            var selected = datasets.require("index_daily_market").definition();
            assertEquals(version, selected.schemaVersion());
            assertEquals(version == 1 ? "timestamp" : "trade_date", selected.designatedTimestamp());
            assertEquals(
                    version, context.getBean(QuestDbIndexDailyMarketTarget.class).datasetVersion());
            assertEquals(
                    selected, context.getBean(IndexDailyMarketReadRepository.class).definition());
            var binding =
                    context.getBean(ReadBindingCatalog.class).bind(datasets).stream()
                            .filter(
                                    value ->
                                            value.definition()
                                                    .datasetId()
                                                    .equals("index_daily_market"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(selected, binding.definition());
            var day = LocalDate.of(2026, 9, 28);
            var request =
                    jobs.prepare(
                            "data.index_daily_market",
                            version,
                            Mode.BACKFILL,
                            Map.of(
                                    "targetId",
                                    "static-v2-" + "a".repeat(64),
                                    "tsCode",
                                    "000300.SH",
                                    "route",
                                    IndexDailyMarketUniverse.resolve("000300.SH").route().name(),
                                    "observedAt",
                                    "2026-09-28T00:00:00Z"),
                            day,
                            day,
                            day);
            assertEquals(version, request.definition().datasetVersion());
            assertFalse(context.getBeanFactory().containsSingleton("questDbClient"));
            verifyNoInteractions(source);
            assertFalse(Files.exists(ledger));
        }
    }
}
