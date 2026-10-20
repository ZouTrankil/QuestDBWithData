package com.zoutrankil.data.stock.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.stock.storage.QuestDbStockSuspendTarget;
import com.zoutrankil.data.stock.storage.StockSuspendReadRepository;

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

class StockSuspendConfiguredLayoutTest {
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
                                                    "app.sync.stk-suspend-schema-version",
                                                    version,
                                                    "app.sync.stk-suspend-table",
                                                    "java_d011_stk_suspend_configured",
                                                    "app.read.stk-suspend-table",
                                                    "java_d011_stk_suspend_public_read",
                                                    "app.sync.ledger-path",
                                                    ledger.toString(),
                                                    "spring.main.banner-mode",
                                                    "off")));
                });
        try (var context = app.run("list-sync-jobs")) {
            var jobs = context.getBean(SyncJobRegistry.class);
            assertEquals(
                    StockSuspendSyncJobOwner.definition(version),
                    jobs.require("data.stk_suspend", version));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> jobs.require("data.stk_suspend", 3 - version));
            var datasets = context.getBean(DatasetRegistry.class);
            var selected = datasets.require("stk_suspend").definition();
            assertEquals(version, selected.schemaVersion());
            assertEquals(version == 1 ? "timestamp" : "trade_date", selected.designatedTimestamp());
            assertEquals(
                    version, context.getBean(QuestDbStockSuspendTarget.class).datasetVersion());
            assertEquals(selected, context.getBean(StockSuspendReadRepository.class).definition());
            assertEquals("java_d011_stk_suspend_public_read", selected.objectName());
            assertEquals(
                    "java_d011_stk_suspend_configured",
                    context.getBean(QuestDbStockSuspendTarget.class).tableName());
            var binding =
                    context.getBean(ReadBindingCatalog.class).bind(datasets).stream()
                            .filter(value -> value.definition().datasetId().equals("stk_suspend"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(selected, binding.definition());
            var day = LocalDate.of(2026, 9, 28);
            var request =
                    jobs.prepare(
                            "data.stk_suspend",
                            version,
                            Mode.BACKFILL,
                            Map.of(
                                    "targetId",
                                    "static-v2-" + "a".repeat(64),
                                    "physicalTargetId",
                                    "static-v2-" + "b".repeat(64)),
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
