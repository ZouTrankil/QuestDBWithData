package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.StockBasicDataset;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ReadBindingCatalogTest {
    @Test void duplicateDatasetIdsAreRejectedEvenWhenVersionsDiffer() {
        var first = typed("sample", 1);
        assertThrows(IllegalArgumentException.class, () -> new ReadBindingCatalog(List.of(first, first)));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadBindingCatalog(List.of(first, typed("sample", 2))));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadBindingCatalog(List.of(first, ReadBindingCatalog.generic("sample", 1))));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "sample-name", "sample.name", "1sample"})
    void invalidDatasetIdentifiersAreRejected(String datasetId) {
        assertThrows(IllegalArgumentException.class, () -> typed(datasetId, 1));
        assertThrows(IllegalArgumentException.class, () -> ReadBindingCatalog.generic(datasetId, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void nonPositiveVersionsAreRejected(int version) {
        assertThrows(IllegalArgumentException.class, () -> typed("sample", version));
        assertThrows(IllegalArgumentException.class, () -> ReadBindingCatalog.generic("sample", version));
    }

    @Test void nullRegistrationPartsAndNullCatalogEntriesAreRejected() {
        assertThrows(NullPointerException.class, () -> new ReadBindingCatalog.Registration<String>(
                "sample", 1, null, row -> "value", () -> null));
        assertThrows(NullPointerException.class, () -> new ReadBindingCatalog.Registration<>(
                "sample", 1, String.class, null, () -> null));
        assertThrows(NullPointerException.class, () -> new ReadBindingCatalog.Registration<>(
                "sample", 1, String.class, row -> "value", null));
        assertThrows(NullPointerException.class, () -> new ReadBindingCatalog(null));
        assertThrows(NullPointerException.class, () -> new ReadBindingCatalog(Collections.singletonList(null)));
        assertThrows(NullPointerException.class, () -> ReadBindingCatalog.generic("sample", 1, null));
        assertThrows(NullPointerException.class, () -> new ReadBindingCatalog(List.of()).bind(null));
    }

    @Test void rowTypesMustBeReferenceValuesAndGenericRowsUseTheirExplicitFactory() {
        assertThrows(IllegalArgumentException.class, () -> new ReadBindingCatalog.Registration<>(
                "sample", 1, int.class, row -> 1, () -> null));
        assertThrows(IllegalArgumentException.class, () -> new ReadBindingCatalog.Registration<>(
                "sample", 1, void.class, row -> null, () -> null));
        assertThrows(IllegalArgumentException.class, () -> new ReadBindingCatalog.Registration<>(
                "sample", 1, Void.class, row -> null, () -> null));
        assertThrows(IllegalArgumentException.class,
                () -> ReadBindingCatalog.typed("sample", 1, DatasetValues.class, Function.identity()));
    }

    @Test void catalogRetainsAnImmutableSnapshotOfExplicitRegistrations() {
        var mutable = new ArrayList<ReadBindingCatalog.Registration<?>>();
        var registered = typed("sample", 1);
        mutable.add(registered);
        var catalog = new ReadBindingCatalog(mutable);
        mutable.clear();
        assertEquals(List.of(registered), catalog.registrations());
        assertThrows(UnsupportedOperationException.class, () -> catalog.registrations().clear());
    }

    @Test void activeVersionMismatchIsRejectedBeforeAnyMapperOrVersionSupplierRuns() {
        var mapperCalls = new AtomicInteger();
        var versionCalls = new AtomicInteger();
        var registration = ReadBindingCatalog.typed("sample", 1, String.class,
                row -> { mapperCalls.incrementAndGet(); return "row"; },
                () -> { versionCalls.incrementAndGet(); return "version"; });
        var catalog = new ReadBindingCatalog(List.of(registration));
        assertThrows(IllegalArgumentException.class, () -> catalog.bind(registry(definition("sample", 2, true))));
        assertEquals(0, mapperCalls.get());
        assertEquals(0, versionCalls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"new_unregistered_dataset", "l2_daily_features"})
    void activeReadDefinitionsNeverAcquireAnImplicitGenericFallback(String datasetId) {
        var catalog = new ReadBindingCatalog(List.of(ReadBindingCatalog.generic("different_dataset", 1)));
        assertThrows(IllegalArgumentException.class, () -> catalog.bind(registry(definition(datasetId, 1, true))));
    }

    @Test void registeredNonReadDatasetIsRejectedButUnregisteredNonReadDatasetIsIgnored() {
        var writeOnly = definition("write_only", 1, false);
        assertThrows(IllegalArgumentException.class,
                () -> new ReadBindingCatalog(List.of(typed("write_only", 1))).bind(registry(writeOnly)));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadBindingCatalog(List.of(ReadBindingCatalog.generic("write_only", 1))).bind(registry(writeOnly)));
        assertTrue(new ReadBindingCatalog(List.of()).bind(registry(writeOnly)).isEmpty());
    }

    @Test void partialRegistriesAllowInactiveCatalogEntriesAndProduceOnlyActiveBindings() {
        var active = definition("active", 1, true);
        var catalog = new ReadBindingCatalog(List.of(typed("not_activated", 3),
                ReadBindingCatalog.generic("active", 1), ReadBindingCatalog.generic("other_inactive", 2)));
        var bindings = catalog.bind(registry(active));
        assertEquals(1, bindings.size());
        assertSame(active, bindings.getFirst().definition());
        assertEquals(DatasetValues.class, bindings.getFirst().rowType());
        assertTrue(catalog.bind(registry()).isEmpty());
        assertThrows(UnsupportedOperationException.class, bindings::clear);
    }

    @Test void bindingPreservesTheFullActiveDefinitionIncludingPhysicalObjectAndPolicy() {
        var base = StockBasicDataset.DEFINITION;
        var active = new DatasetDefinition("isolated_sample", 7, "private.provider", "isolated_owner",
                "java_read_scope_20261007", base.objectKind(), base.columns(), base.businessKey(), base.dedupKey(),
                base.designatedTimestamp(), DatasetDefinition.Partition.MONTH, base.wal(),
                Set.of(DatasetDefinition.Capability.READ, DatasetDefinition.Capability.WRITE), List.of(),
                "Retain the configured physical object and exact policy rationale");
        var registration = typed(active.datasetId(), active.schemaVersion());
        var binding = new ReadBindingCatalog(List.of(registration)).bind(registry(active)).getFirst();
        assertSame(active, binding.definition());
        assertEquals("java_read_scope_20261007", binding.definition().objectName());
        assertEquals(DatasetDefinition.Partition.MONTH, binding.definition().partition());
        assertEquals(String.class, binding.rowType());
        assertSame(registration.mapper(), binding.mapper());
        assertSame(registration.sourceVersion(), binding.sourceVersion());
    }

    @Test void genericBindingKeepsIdentityAndEvaluatesItsSupplierOnlyForEachRead() {
        var calls = new AtomicInteger();
        var active = definition("generic_sample", 1, true);
        var registration = ReadBindingCatalog.generic(active.datasetId(), 1, () -> "generation-" + calls.incrementAndGet());
        var binding = new ReadBindingCatalog(List.of(registration)).bind(registry(active)).getFirst();
        assertEquals(0, calls.get());
        var row = new DatasetValues(Map.of("ts_code", "000001.SZ"));
        assertSame(row, registration.mapper().apply(row));
        assertSame(row, binding.mapper().apply(row));
        var backend = new RecordingReader(row);
        var query = query();
        var first = binding.read(backend, query);
        var second = binding.read(backend, query);
        assertEquals(2, calls.get());
        assertEquals(List.of("generation-1", "generation-2"), backend.versions);
        assertSame(active, backend.definition);
        assertSame(query, backend.query);
        assertSame(row, first.rows().getFirst());
        assertSame(row, second.rows().getFirst());
        assertEquals("generation-1", first.sourceVersion());
        assertEquals("generation-2", second.sourceVersion());
    }

    @Test void factoriesWithoutAnExplicitSupplierKeepNullSourceVersion() {
        var raw = definition("raw", 1, true);
        var typedDefinition = definition("typed", 1, true);
        var catalog = new ReadBindingCatalog(List.of(ReadBindingCatalog.generic("raw", 1), typed("typed", 1)));
        var backend = new RecordingReader(new DatasetValues(Map.of("ts_code", "000001.SZ")));
        for (var binding : catalog.bind(registry(raw, typedDefinition))) {
            assertNull(binding.sourceVersion().get());
            assertNull(binding.read(backend, query()).sourceVersion());
        }
        assertEquals(Arrays.asList(null, null), backend.versions);
    }

    private static ReadBindingCatalog.Registration<String> typed(String id, int version) {
        return ReadBindingCatalog.typed(id, version, String.class, row -> row.get("ts_code", String.class));
    }

    private static DatasetDefinition definition(String id, int version, boolean readable) {
        var base = StockBasicDataset.DEFINITION;
        return new DatasetDefinition(id, version, base.provider(), base.owner(), "table_" + id,
                base.objectKind(), base.columns(), base.businessKey(), base.dedupKey(), base.designatedTimestamp(),
                base.partition(), base.wal(), readable ? Set.of(DatasetDefinition.Capability.READ)
                        : Set.of(DatasetDefinition.Capability.WRITE), List.of(), base.storageRationale());
    }

    private static DatasetRegistry registry(DatasetDefinition... definitions) {
        return new DatasetRegistry(Arrays.stream(definitions).map(definition -> (DatasetImplementation) () -> definition).toList());
    }

    private static DatasetReadQuery query() {
        return new DatasetReadQuery(List.of("snapshot_ts", "ts_code"), Map.of(), null, null, null, 1, null);
    }

    private static final class RecordingReader extends QuestDbBoundedReader {
        private final DatasetValues row;
        private final List<String> versions = new ArrayList<>();
        private DatasetDefinition definition;
        private DatasetReadQuery query;
        private RecordingReader(DatasetValues row) { super(null); this.row = row; }
        @Override public <T> DatasetReadPage<T> read(DatasetDefinition definition, DatasetReadQuery query,
                                                    String sourceVersion, Function<DatasetValues,T> mapper) {
            this.definition = definition;
            this.query = query;
            versions.add(sourceVersion);
            return new DatasetReadPage<>(definition.datasetId(), definition.schemaVersion(), sourceVersion,
                    Instant.parse("2026-10-07T00:00:00Z"), List.of(mapper.apply(row)), null);
        }
    }
}
