package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.StringEntry;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SourceStrategiesTest {
    @Test void explicitRegistryCoversEverySupportedDatasetAndCannotBeMutated() {
        assertEquals(41, SourceStrategies.datasets().size());
        assertEquals(SourceContract.SUPPORTED, SourceStrategies.datasets());
        assertThrows(UnsupportedOperationException.class, () -> SourceStrategies.datasets().remove("daily"));
        for (String dataset : SourceStrategies.datasets()) {
            var strategy = SourceStrategies.require(dataset);
            assertSame(strategy, SourceStrategies.require(dataset));
            assertNotNull(strategy.request());
            assertNotNull(strategy.rows());
            assertNotNull(strategy.coverage());
            assertNotNull(strategy.storage());
        }
    }

    @Test void genericCollectorAndItsRequestRecordsContainNoDatasetDispatchLiterals() throws Exception {
        for (Class<?> type : List.of(SourceCollector.class, SourceCollector.Request.class, SourceCollector.Frozen.class,
                SourceCollector.Collected.class, SourceCollector.ContractFetcher.class)) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertNotNull(stream, type.getName());
                var matches = new TreeSet<String>();
                for (var entry : ClassFile.of().parse(stream.readAllBytes()).constantPool()) {
                    if (entry instanceof StringEntry literal && SourceStrategies.datasets().contains(literal.stringValue())) {
                        matches.add(literal.stringValue());
                    }
                }
                assertTrue(matches.isEmpty(), () -> type.getName() + " embeds dataset dispatch literals: " + matches);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "DAILY", "unregistered", "daily-v1", "daily "})
    void unknownDatasetCannotFallBackToAGenericStrategy(String dataset) {
        assertThrows(IllegalArgumentException.class, () -> SourceStrategies.require(dataset));
        assertThrows(IllegalArgumentException.class, () -> SourceStrategies.index(List.of(
                new SourceStrategies.Registration(dataset, SourceStrategies.require("daily")))));
    }

    @Test void duplicateRegistrationIsRejectedEvenWhenTheStrategyIsIdentical() {
        var registration = new SourceStrategies.Registration("daily", SourceStrategies.require("daily"));
        assertThrows(IllegalArgumentException.class, () -> SourceStrategies.index(List.of(registration, registration)));
        assertThrows(IllegalArgumentException.class, () -> SourceStrategies.index(List.of(registration,
                new SourceStrategies.Registration("daily", SourceStrategies.require("stk_st_daily")))));
    }

    @Test void registryIndexCopiesItsInputAndExposesAnImmutableMap() {
        var strategy = SourceStrategies.require("daily");
        var input = new ArrayList<>(List.of(new SourceStrategies.Registration("daily", strategy)));
        var index = SourceStrategies.index(input);
        input.clear();
        assertEquals(Set.of("daily"), index.keySet());
        assertSame(strategy, index.get("daily"));
        assertThrows(UnsupportedOperationException.class, () -> index.put("daily_basic", strategy));
        assertThrows(UnsupportedOperationException.class, () -> index.remove("daily"));
    }

    @Test void registryRejectsNullInputsAndIncompleteRegistrations() {
        assertThrows(NullPointerException.class, () -> SourceStrategies.index(null));
        assertThrows(NullPointerException.class, () -> SourceStrategies.index(Collections.singletonList(null)));
        assertThrows(NullPointerException.class, () -> new SourceStrategies.Registration(null, SourceStrategies.require("daily")));
        assertThrows(NullPointerException.class, () -> new SourceStrategies.Registration("daily", null));
        assertThrows(IllegalArgumentException.class, () -> SourceStrategies.require(null));
    }

    @ParameterizedTest @ValueSource(strings = {"request", "rows", "coverage", "storage"})
    void completeStrategyRequiresEveryPolicy(String absent) {
        var daily = SourceStrategies.require("daily");
        assertThrows(NullPointerException.class, () -> new SourceStrategy(
                absent.equals("request") ? null : daily.request(),
                absent.equals("rows") ? null : daily.rows(),
                absent.equals("coverage") ? null : daily.coverage(),
                absent.equals("storage") ? null : daily.storage()));
    }

    @Test void eachRegisteredPolicyOpensAFreshCollectionSession() {
        for (String dataset : SourceStrategies.datasets()) {
            var contract = SourceContract.load(dataset);
            Set<String> codes = switch (dataset) {
                case "exchange_calendar", "fut_daily", "fut_settle", "fut_mapping", "ft_limit", "fut_holding", "fut_basic" -> Set.of();
                case "etf_basic" -> Set.of("E");
                default -> contract.isMarketAggregate() ? Set.of() : Set.of("000001.SZ");
            };
            var request = NativeSourceTest.request(dataset, codes);
            var strategy = SourceStrategies.require(dataset);
            var first = strategy.request().openSession(contract, request, strategy.rows());
            var second = strategy.request().openSession(contract, request, strategy.rows());
            assertNotNull(first, dataset);
            assertNotNull(second, dataset);
            assertNotSame(first, second, dataset);
        }
    }
}
