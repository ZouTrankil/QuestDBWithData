package com.zoutrankil.data.etf.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.etf.storage.EtfShareReadRepository;
import com.zoutrankil.data.etf.storage.QuestDbEtfShareTarget;
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

class EtfShareConfiguredLayoutTest {
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
                                                    "app.sync.etf-share-schema-version",
                                                    version,
                                                    "app.sync.etf-share-table",
                                                    "java_d016_etf_share_configured",
                                                    "app.sync.ledger-path",
                                                    ledger.toString(),
                                                    "spring.main.banner-mode",
                                                    "off")));
                });
        try (var context = app.run("list-sync-jobs")) {
            var jobs = context.getBean(SyncJobRegistry.class);
            assertEquals(
                    EtfShareSyncJobOwner.forLayout(version),
                    jobs.require("data.etf_share", version + 2));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> jobs.require("data.etf_share", 5 - version));
            var datasets = context.getBean(DatasetRegistry.class);
            var selected = datasets.require("etf_share").definition();
            assertEquals(version, selected.schemaVersion());
            assertEquals(version == 1 ? "timestamp" : "trade_date", selected.designatedTimestamp());
            assertEquals(version, context.getBean(QuestDbEtfShareTarget.class).datasetVersion());
            assertEquals(selected, context.getBean(EtfShareReadRepository.class).definition());
            var binding =
                    context.getBean(ReadBindingCatalog.class).bind(datasets).stream()
                            .filter(value -> value.definition().datasetId().equals("etf_share"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(selected, binding.definition());
            var day = LocalDate.of(2026, 9, 28);
            var request =
                    jobs.prepare(
                            "data.etf_share",
                            version + 2,
                            Mode.BACKFILL,
                            Map.of(
                                    "targetId",
                                    "static-v2-" + "a".repeat(64),
                                    "trade_dates",
                                    "20260928",
                                    "observedAt",
                                    "2026-09-28T12:34:56.123456Z"),
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
