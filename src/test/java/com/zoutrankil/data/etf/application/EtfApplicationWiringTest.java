package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.etf.storage.*;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;

import javax.sql.DataSource;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtfApplicationWiringTest {
    @TempDir Path temp;

    @Test void movedOwnersReadersAndGenericTargetPortsWireOnceWhileCatalogPlanningStaysOffline() throws Exception {
        var dataSource = mock(DataSource.class);
        Path ledger = temp.resolve("not-created.sqlite");
        var tables = Map.of("etf_daily", "java_d014_etf_daily_wiring",
                "etf_adj", "java_d015_etf_adj_wiring", "etf_factor", "java_d017_etf_factor_wiring");
        var application = new SpringApplication(QuestDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("dataSource", dataSource);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("etf-wiring-test", Map.of(
                    "app.sync.ledger-path", ledger.toString(), "spring.main.banner-mode", "off",
                    "app.sync.etf-daily-table", tables.get("etf_daily"),
                    "app.sync.etf-adj-table", tables.get("etf_adj"),
                    "app.sync.etf-factor-table", tables.get("etf_factor"))));
        });
        try (var context = application.run("plan-sync-job", "--job", "data.etf_daily", "--version", "1",
                "--mode", "BACKFILL", "--from", "2026-09-28", "--to", "2026-09-28",
                "--logical-date", "2026-09-28", "--parameters",
                "{\"targetId\":\"static-v2-frozen\",\"trade_dates\":\"20260928\"}")) {
            var owners = context.getBeansOfType(SyncJobOwner.class).values();
            var jobs = context.getBean(SyncJobRegistry.class);
            var datasets = context.getBean(DatasetRegistry.class);
            var catalog = context.getBean(ReadBindingCatalog.class);
            var expectedOwners = Map.of("etf_daily", EtfDailySyncJobOwner.class,
                    "etf_adj", EtfAdjSyncJobOwner.class, "etf_factor", EtfFactorSyncJobOwner.class);
            var expectedDefinitions = Map.of("etf_daily", EtfDailySyncJobOwner.DEFINITION,
                    "etf_adj", EtfAdjSyncJobOwner.DEFINITION, "etf_factor", EtfFactorSyncJobOwner.DEFINITION);
            var expectedTargets = Map.of("etf_daily", QuestDbEtfDailyTarget.class,
                    "etf_adj", QuestDbEtfAdjTarget.class, "etf_factor", QuestDbEtfFactorTarget.class);
            var targets = context.getBeansOfType(EtfTarget.class).values().stream()
                    .filter(target -> expectedTargets.containsValue(target.getClass())).toList();
            assertEquals(3, targets.size());
            assertEquals(jobs.definitions().size(), jobs.definitions().stream().map(SyncJobDefinition::jobId).distinct().count());
            assertEquals(56, datasets.definitions().stream()
                    .filter(d -> d.capabilities().contains(DatasetDefinition.Capability.READ)).count());
            var bindings = catalog.bind(datasets);
            assertEquals(56, bindings.size());
            for (String dataset : tables.keySet()) {
                var familyOwners = owners.stream().filter(owner -> owner.datasetId().equals(dataset)).toList();
                assertEquals(1, familyOwners.size());
                assertEquals(expectedOwners.get(dataset), familyOwners.getFirst().getClass());
                assertSame(expectedDefinitions.get(dataset), jobs.require("data." + dataset, 1));
                var target = targets.stream().filter(value -> value.tableName().equals(tables.get(dataset))).findFirst().orElseThrow();
                assertEquals(expectedTargets.get(dataset), target.getClass());
                var binding = bindings.stream().filter(value -> value.definition().datasetId().equals(dataset)).findFirst().orElseThrow();
                assertEquals(tables.get(dataset), binding.definition().objectName());
                assertEquals(DatasetValues.class, binding.rowType(), "These three group-reader representations stay generic");
                assertNull(binding.sourceVersion().get());
                var frozen = SyncJobPlanning.prepare(jobs, Map.of("--job", "data." + dataset, "--version", "1",
                        "--mode", "BACKFILL", "--from", "2026-09-28", "--to", "2026-09-28",
                        "--logical-date", "2026-09-28", "--parameters",
                        "{\"targetId\":\"static-v2-frozen\",\"trade_dates\":\"20260928\"}"));
                assertEquals(expectedDefinitions.get(dataset), frozen.definition());
                assertEquals(LocalDate.of(2026, 9, 28), frozen.from());
                assertEquals("static-v2-frozen", frozen.parameters().get("targetId"));
            }
            assertEquals(1, context.getBeansOfType(EtfDailyJobService.class).size());
            assertEquals(1, context.getBeansOfType(EtfAdjJobService.class).size());
            assertEquals(1, context.getBeansOfType(EtfFactorJobService.class).size());
            assertEquals(tables.get("etf_daily"), context.getBean(EtfDailyJobService.class).tableName());
            assertEquals(tables.get("etf_adj"), context.getBean(EtfAdjJobService.class).tableName());
            assertEquals(tables.get("etf_factor"), context.getBean(EtfFactorJobService.class).tableName());
            assertEquals(1, context.getBeansOfType(EtfDailyReadRepository.class).size());
            assertEquals(1, context.getBeansOfType(EtfAdjReadRepository.class).size());
            assertEquals(1, context.getBeansOfType(EtfFactorReadRepository.class).size());
            assertFalse(context.getBeanFactory().containsSingleton("questDbClient"));
            assertFalse(Files.exists(ledger));
            verify(dataSource, never()).getConnection();
            verify(dataSource, never()).getConnection(anyString(), anyString());
        }
        assertFalse(Files.exists(ledger));
    }
}
