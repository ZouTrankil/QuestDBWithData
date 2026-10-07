package com.zoutrankil.data.config;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.StockBasicDataset;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.ReadGroupReader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadBindingApplicationTest {
    @TempDir Path temp;

    @Test void springCollectsAnExplicitExtensionWithoutInvokingItsMapperOrBackend() {
        var base = StockBasicDataset.DEFINITION;
        var definition = new DatasetDefinition("extension_sample", 1, base.provider(), base.owner(),
                "extension_table", base.objectKind(), base.columns(), base.businessKey(), base.dedupKey(),
                base.designatedTimestamp(), base.partition(), base.wal(), base.capabilities(), List.of(), base.storageRationale());
        var backend = mock(QuestDbBoundedReader.class);
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(DatasetRegistry.class, () -> new DatasetRegistry(List.of(() -> definition)));
            context.registerBean(QuestDbBoundedReader.class, () -> backend);
            context.registerBean(ReadBindingCatalog.Registration.class, () -> ReadBindingCatalog.typed(
                    definition.datasetId(), definition.schemaVersion(), String.class,
                    row -> { throw new AssertionError("Assembly must not invoke a mapper"); }));
            context.register(ReadGroupConfiguration.class);
            context.refresh();
            var catalog = context.getBean(ReadBindingCatalog.class);
            assertEquals(57, catalog.registrations().size());
            var binding = catalog.bind(context.getBean(DatasetRegistry.class)).getFirst();
            assertSame(definition, binding.definition());
            assertEquals(String.class, binding.rowType());
            assertNotNull(context.getBean(ReadGroupReader.class));
            verifyNoInteractions(backend);
        }
    }

    @Test void springRejectsAnExtensionThatDuplicatesAnExistingPublicRepresentation() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(DatasetRegistry.class, () -> new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION)));
            context.registerBean(QuestDbBoundedReader.class, () -> mock(QuestDbBoundedReader.class));
            context.registerBean(ReadBindingCatalog.Registration.class,
                    () -> ReadBindingCatalog.generic(StockBasicDataset.DEFINITION.datasetId(), 1));
            context.register(ReadGroupConfiguration.class);
            var failure = assertThrows(org.springframework.beans.BeansException.class, context::refresh);
            assertTrue(failure.getMostSpecificCause().getMessage().contains("Duplicate read registration"));
        }
    }

    @Test void directTwoArgumentConfigurationStillSupportsAPartialRegistry() {
        var backend = mock(QuestDbBoundedReader.class);
        var datasets = new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION));
        assertNotNull(new ReadGroupConfiguration().readGroupReader(datasets, backend));
        verifyNoInteractions(backend);
    }

    @Test void completeApplicationRegistersEveryActiveReaderWithoutDatabaseAccess() throws Exception {
        var expectedTyped = originalTypedRows();
        assertEquals(20, expectedTyped.size());
        var dataSource = mock(DataSource.class);
        var ledger = temp.resolve("not-created.sqlite");
        var application = new SpringApplication(QuestDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("dataSource", dataSource);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("read-binding-test", Map.of(
                    "app.sync.ledger-path", ledger.toString(), "spring.main.banner-mode", "off")));
        });
        try (var context = application.run("plan-sync-job", "--job", "data.stock_basic", "--version", "2",
                "--logical-date", "2026-10-07", "--parameters", "{\"codes\":[\"000001.SZ\"]}")) {
            var datasets = context.getBean(DatasetRegistry.class);
            var catalog = context.getBean(ReadBindingCatalog.class);
            assertNotNull(context.getBean(ReadGroupReader.class));
            var typed = new LinkedHashMap<String, String>();
            for (var registration : catalog.registrations())
                if (registration.rowType() != DatasetValues.class)
                    typed.put(registration.datasetId(), registration.rowType().getName());
            assertEquals(expectedTyped, typed);
            var bound = catalog.bind(datasets);
            var readable = datasets.definitions().stream()
                    .filter(definition -> definition.capabilities().contains(DatasetDefinition.Capability.READ)).toList();
            var historicalIds = new LinkedHashSet<>(expectedTyped.keySet());
            historicalIds.addAll(originalGenericIds());
            assertEquals(54, historicalIds.size(), "The historical contract remains 20 typed and 34 generic readers");
            var addedIds = Set.of("market_sentiment_daily", "regime_features_monitor_daily");
            var readableIds = readable.stream().map(DatasetDefinition::datasetId).collect(Collectors.toSet());
            assertEquals(historicalIds, readableIds.stream().filter(id -> !addedIds.contains(id)).collect(Collectors.toSet()));
            assertTrue(readableIds.containsAll(addedIds));
            assertEquals(56, readable.size());
            assertEquals(56, catalog.registrations().size());
            assertEquals(readable.size(), bound.size());
            for (var id : addedIds) {
                var registration = catalog.registrations().stream().filter(value -> value.datasetId().equals(id))
                        .findFirst().orElseThrow();
                assertEquals(1, registration.schemaVersion());
                assertEquals(DatasetValues.class, registration.rowType());
                var row = new DatasetValues(Map.of("explicit-generic", id));
                assertSame(row, registration.mapper().apply(row));
                assertNull(registration.sourceVersion().get());
            }
            for (var definition : readable) {
                var binding = bound.stream().filter(value -> value.definition().datasetId().equals(definition.datasetId()))
                        .findFirst().orElseThrow();
                assertEquals(definition, binding.definition());
                assertEquals(expectedTyped.getOrDefault(definition.datasetId(), DatasetValues.class.getName()),
                        binding.rowType().getName());
                assertNull(binding.sourceVersion().get());
            }
            assertEquals(DatasetValues.class, bound.stream()
                    .filter(value -> value.definition().datasetId().equals("l2_event_response_features"))
                    .findFirst().orElseThrow().rowType());
            assertFalse(context.getBeanFactory().containsSingleton("stockBasicScheduleService"));
            assertFalse(Files.exists(ledger));
            verify(dataSource, never()).getConnection();
            verify(dataSource, never()).getConnection(anyString(), anyString());
        }
        assertFalse(Files.exists(ledger));
    }

    /** The original 34 generic entries; additions must not rewrite this historical contract. */
    private static Set<String> originalGenericIds() {
        return Set.of(
                "daily", "daily_basic", "dc_index", "etf_adj",
                "etf_basic", "etf_daily", "etf_factor", "etf_portfolio",
                "etf_share", "exchange_calendar", "index", "index_daily_basic",
                "index_daily_market", "index_member", "index_monthly", "index_weight",
                "l2_event_response_features", "margin_all", "margin_detail", "margin_secs",
                "margin_zrz", "moneyflow", "moneyflow_dc", "moneyflow_hsgt",
                "moneyflow_ths", "stk_factor", "stk_limit", "stk_st_daily",
                "stk_suspend", "stock_basic_latest", "stock_basic_snapshot", "stock_detail_info",
                "ths_index", "ths_member");
    }

    private Map<String, String> originalTypedRows() throws Exception {
        try (var stream = getClass().getResourceAsStream("/read-bindings-before-t11.tsv")) {
            assertNotNull(stream);
            var rows = new LinkedHashMap<String, String>();
            for (var line : new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).lines().toList()) {
                var fields = line.split("\t", -1);
                assertEquals(2, fields.length);
                assertNull(rows.put(fields[0], fields[1]));
            }
            return rows;
        }
    }
}
