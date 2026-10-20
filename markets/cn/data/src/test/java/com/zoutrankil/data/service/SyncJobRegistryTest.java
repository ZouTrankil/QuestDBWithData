package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.JobRef;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.stock.basic.StockBasicDataset;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

class SyncJobRegistryTest {
    private final DatasetRegistry datasets =
            new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION));
    private final SyncJobRegistry.Policies policies =
            new SyncJobRegistry.Policies(Set.of("shared"), Set.of("codes"), Set.of("full_values"));

    private SyncJobDefinition job(
            String id, String dataset, int version, boolean enabled, List<JobRef> dependencies) {
        return new SyncJobDefinition(
                id,
                version,
                dataset,
                1,
                "sample",
                Set.of(Mode.SNAPSHOT),
                Mode.SNAPSHOT,
                Map.of(),
                "shared",
                "codes",
                "full_values",
                new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(5)),
                Duration.ofSeconds(10),
                new Budget(10, 2, 2, 2, 1024),
                0,
                dependencies,
                Frequency.MANUAL,
                ZoneOffset.UTC,
                enabled,
                false);
    }

    private SyncJobRegistry registry(SyncJobDefinition... jobs) {
        return new SyncJobRegistry(
                List.of(jobs),
                datasets,
                Map.of("stock_basic_snapshot", Set.of(Mode.SNAPSHOT)),
                policies);
    }

    @Test
    void rejectsUnknownDatasetDuplicateJobAndMissingAdapterMode() {
        var valid = job("stock", "stock_basic_snapshot", 1, false, List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> registry(job("stock", "missing", 1, false, List.of())));
        assertThrows(IllegalArgumentException.class, () -> registry(valid, valid));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SyncJobRegistry(List.of(valid), datasets, Map.of(), policies));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SyncJobRegistry(
                                List.of(valid),
                                datasets,
                                Map.of("stock_basic_snapshot", Set.of(Mode.SNAPSHOT)),
                                new SyncJobRegistry.Policies(
                                        Set.of(), Set.of("codes"), Set.of("full_values"))));
    }

    @Test
    void validatesDependencyVersionsAndRejectsCycles() {
        var a = job("a", "stock_basic_snapshot", 1, false, List.of(new JobRef("b", 1)));
        var b = job("b", "stock_basic_snapshot", 1, false, List.of(new JobRef("a", 1)));
        assertThrows(IllegalArgumentException.class, () -> registry(a));
        assertThrows(IllegalArgumentException.class, () -> registry(a, b));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry(a, job("b", "stock_basic_snapshot", 2, false, List.of())));
        assertEquals(
                2,
                registry(a, job("b", "stock_basic_snapshot", 1, false, List.of()))
                        .definitions()
                        .size());
    }

    @Test
    void disabledJobsCannotPrepareAndCatalogReplacementPreservesFrozenVersion() {
        var day = LocalDate.of(2026, 9, 29);
        var old = registry(job("stock", "stock_basic_snapshot", 1, true, List.of()));
        var frozen = old.prepare("stock", 1, null, Map.of(), null, null, day);
        var replacement = registry(job("stock", "stock_basic_snapshot", 2, false, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> replacement.prepare("stock", 2, null, Map.of(), null, null, day));
        assertThrows(IllegalArgumentException.class, () -> replacement.require("stock", 1));
        assertEquals(1, frozen.definition().version());
        assertTrue(old.dailyJobs().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> old.definitions().clear());
    }
}
