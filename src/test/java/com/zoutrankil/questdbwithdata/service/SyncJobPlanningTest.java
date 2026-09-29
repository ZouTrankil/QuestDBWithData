package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncJobPlanningTest {
    private SyncJobRegistry registry() {
        var d = StockBasicSyncAdapter.definition(true);
        return new SyncJobRegistry(List.of(d), new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION)),
                Map.of(d.datasetId(), d.supportedModes()), new SyncJobRegistry.Policies(Set.of(d.ratePolicyRef()),
                Set.of(d.slicePolicyRef()), Set.of(d.verificationPolicyRef())));
    }
    private Map<String,String> options(String parameters) {
        return new HashMap<>(Map.of("--job", "data.stock_basic", "--version", "2",
                "--logical-date", "2026-09-29", "--parameters", parameters));
    }
    @Test void freezesCatalogDefaultAndTypedParametersWithoutRuntimeDependencies() throws Exception {
        var frozen = SyncJobPlanning.prepare(registry(), options("{\"codes\":[\"000001.SZ\"]}"));
        assertEquals(SyncJobDefinition.Mode.SNAPSHOT, frozen.mode());
        assertEquals(List.of("000001.SZ"), frozen.parameters().get("codes"));
        assertEquals(LocalDate.of(2026,9,29), frozen.logicalDate());
        assertNull(frozen.from());
    }
    @Test void rejectsAmbiguousJsonUnsupportedModesAndMissingWindowBound() {
        for (var input : List.of("null", "[]", "{}", "{\"codes\":\"000001.SZ\"}",
                "{\"codes\":[1]}", "{\"codes\":[\"000001.SZ\",\"000001.SZ\"]}",
                "{\"codes\":[\"000001.SZ\"],\"codes\":[\"600000.SH\"]}",
                "{\"codes\":[\"000001.SZ\"]} {}", "{\"unexpected\":true}"))
            assertThrows(Exception.class, () -> SyncJobPlanning.prepare(registry(), options(input)));
        var mode = options("{\"codes\":[\"000001.SZ\"]}");
        mode.put("--mode", "incremental");
        assertThrows(IllegalArgumentException.class, () -> SyncJobPlanning.prepare(registry(), mode));
        var window = options("{\"codes\":[\"000001.SZ\"]}");
        window.put("--from", "2026-09-29");
        assertThrows(IllegalArgumentException.class, () -> SyncJobPlanning.prepare(registry(), window));
        var hiddenWrite = options("{\"codes\":[\"000001.SZ\"]}");
        hiddenWrite.put("--run", "true");
        assertThrows(IllegalArgumentException.class, () -> SyncJobPlanning.prepare(registry(), hiddenWrite));
    }
}
