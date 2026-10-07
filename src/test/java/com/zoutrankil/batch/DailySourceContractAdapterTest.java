package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.DailySemantics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DailySourceContractAdapterTest {
    @Test void bindsTheIndependentOriginalContractToOrderedSharedFieldsAndKeys() throws Exception {
        var original = original();
        var bound = DailySourceContractAdapter.bind(original);
        assertEquals(original, bound);
        assertArrayEquals(DailySemanticsCompatibilityTest.golden("source-contract.json"), Json.MAPPER.writeValueAsBytes(bound));
        assertEquals(DailySemantics.V1.sourceFields(), bound.columns().stream().map(SourceContract.Column::source).toList());
        assertEquals(DailySemantics.V1.storageFields(), bound.columns().stream().map(SourceContract.Column::target).toList());
        assertEquals(List.of("ts_code", "trade_date"), bound.columns().stream().filter(SourceContract.Column::key)
                .map(SourceContract.Column::source).toList());
        assertThrows(UnsupportedOperationException.class, () -> bound.columns().clear());
        assertEquals(bound, DailySourceContractAdapter.bind(bound));
    }

    @ParameterizedTest @ValueSource(strings = {"DAY", "MONTH", "YEAR"})
    void preservesAllRuntimePolicyWithoutDerivingItFromSharedSemantics(String partition) throws Exception {
        var original = original();
        var runtime = new SourceContract("daily", "daily-v1", "runtime_endpoint", "query_day",
                "trade_date", "trade_date", 17, true, true, 113, 4096,
                "runtime source documentation", original.columns(), partition);
        var bound = DailySourceContractAdapter.bind(runtime);
        assertEquals(runtime, bound);
        assertArrayEquals(Json.MAPPER.writeValueAsBytes(runtime), Json.MAPPER.writeValueAsBytes(bound));
        assertEquals(partition, bound.partitionBy());
        assertEquals(17, bound.pageContract().pageSize());
        assertEquals("query_day", bound.dateParameter());
    }

    @ParameterizedTest @ValueSource(strings = {"dataset", "version", "sourceDate", "timestampColumn",
            "sourceName", "targetName", "type", "key", "order", "missing", "extra"})
    void refusesDriftInEverySharedSemanticComponent(String drift) throws Exception {
        ObjectNode tree = originalInput();
        var columns = (ArrayNode)tree.get("columns");
        switch (drift) {
            case "dataset" -> tree.put("dataset", "daily_basic");
            case "version" -> tree.put("version", "daily-v2");
            case "sourceDate" -> tree.put("sourceDate", "alternate_date");
            case "timestampColumn" -> {
                tree.put("timestampColumn", "alternate_date");
                ((ObjectNode)columns.get(1)).put("target", "alternate_date");
            }
            case "sourceName" -> ((ObjectNode)columns.get(2)).put("source", "open_price");
            case "targetName" -> ((ObjectNode)columns.get(2)).put("target", "open_price");
            case "type" -> ((ObjectNode)columns.get(2)).put("type", "LONG");
            case "key" -> ((ObjectNode)columns.get(0)).put("key", false);
            case "order" -> {
                var firstMetric = columns.get(2); var secondMetric = columns.get(3);
                columns.set(2, secondMetric); columns.set(3, firstMetric);
            }
            case "missing" -> columns.remove(columns.size() - 1);
            case "extra" -> columns.addObject().put("source", "extra_metric").put("target", "extra_metric")
                    .put("type", "DOUBLE").put("key", false);
            default -> throw new AssertionError(drift);
        }
        // The existing SourceContract constructor accepts every case. The new boundary must reject it.
        var loaded = Json.MAPPER.treeToValue(tree, SourceContract.class);
        assertThrows(IllegalArgumentException.class, () -> DailySourceContractAdapter.bind(loaded), drift);
    }

    @Test void requiresAnExplicitContract() {
        assertThrows(NullPointerException.class, () -> DailySourceContractAdapter.bind(null));
    }

    @ParameterizedTest @MethodSource("otherDatasets")
    void otherRegisteredSourcesRetainTheirOwnSerializedContracts(String dataset) throws Exception {
        try (var stream = SourceContract.class.getResourceAsStream("/contracts/source/" + dataset + ".json")) {
            assertNotNull(stream);
            var independentlyLoaded = Json.MAPPER.readValue(stream, SourceContract.class);
            var actual = SourceContract.load(dataset);
            assertEquals(independentlyLoaded, actual);
            assertArrayEquals(Json.MAPPER.writeValueAsBytes(independentlyLoaded), Json.MAPPER.writeValueAsBytes(actual));
        }
    }

    private static Stream<String> otherDatasets() {
        assertEquals(41, SourceContract.SUPPORTED.size());
        return SourceContract.SUPPORTED.stream().filter(dataset -> !dataset.equals("daily")).sorted();
    }
    private static SourceContract original() throws Exception {
        return Json.MAPPER.treeToValue(originalInput(), SourceContract.class);
    }
    private static ObjectNode originalInput() throws Exception {
        var tree = (ObjectNode)Json.MAPPER.readTree(DailySemanticsCompatibilityTest.golden("source-contract.json"));
        // The old serializer emits this calculated getter, but source JSON accepts only record components.
        assertFalse(tree.remove("marketAggregate").booleanValue());
        return tree;
    }
}
