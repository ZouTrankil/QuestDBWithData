package com.zoutrankil.data.derived.mapper;


import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.MarketBreadthDailyCacheRow;
import com.zoutrankil.data.derived.storage.MarketBreadthDailyCacheReadRepository;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MarketBreadthDailyCacheMappingTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 17);
    private static final String VERSION = "a".repeat(64), NEXT_VERSION = "b".repeat(64);
    private static final List<String> FIELDS = List.of("trade_date", "stock_count", "up_count", "down_count",
            "flat_count", "avg_pct_change", "total_amount_yi", "source_version");
    private final MarketBreadthDailyCacheMapper mapper = new MarketBreadthDailyCacheMapper();

    @Test void allEightFieldsRoundTripWithoutReapplyingAggregateUnitConversions() {
        var storage = new MarketBreadthDailyCacheRow(Instant.parse("2026-09-17T00:00:00Z"),
                5552L, 2575L, 2820L, 157L, 0.12166565201729085, 18343.331240500058, VERSION);
        var actual = mapper.fromStorage(storage);
        assertEquals(new MarketBreadthDailyCache(DAY, 5552, 2575, 2820, 157,
                storage.avgPctChange(), storage.totalAmountYi(), VERSION), actual);
        assertEquals(actual, mapper.fromValues(mapper.values(actual)));
        assertEquals(FIELDS, mapper.values(actual).columns().stream().toList());
        assertEquals(Double.doubleToLongBits(storage.avgPctChange()), Double.doubleToLongBits(actual.avgPctChange()));
        assertEquals(Double.doubleToLongBits(storage.totalAmountYi()), Double.doubleToLongBits(actual.totalAmountYi()));
        assertEquals(new MarketBreadthDailyCacheKey(DAY, VERSION), actual.key());
    }

    @Test void nullableAggregatesAndNullSourceChangeCountsRemainUncoerced() {
        var cache = mapper.fromStorage(new MarketBreadthDailyCacheRow(Instant.parse("2026-09-17T00:00:00Z"),
                2L, 0L, 0L, 0L, null, null, VERSION));
        assertEquals(cache, mapper.fromValues(mapper.values(cache)));
        assertEquals(2, cache.stockCount()); assertEquals(0, cache.flatCount());
        assertNull(cache.avgPctChange()); assertNull(cache.totalAmountYi());
    }

    @Test void completeIdentityKeepsTwoGenerationsForOneDateDistinct() {
        var first = new MarketBreadthDailyCacheKey(DAY, VERSION);
        var second = new MarketBreadthDailyCacheKey(DAY, NEXT_VERSION);
        assertNotEquals(first, second);
        assertEquals(2, Set.of(first, second).size());
        assertThrows(NullPointerException.class, () -> new MarketBreadthDailyCacheKey(null, VERSION));
        assertThrows(IllegalArgumentException.class, () -> new MarketBreadthDailyCacheKey(DAY, null));
        for (var invalid : List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), " " + VERSION))
            assertThrows(IllegalArgumentException.class, () -> new MarketBreadthDailyCacheKey(DAY, invalid));
    }

    @Test void exactCalendarCarrierRequiredAndMissingOrWrongPhysicalFieldsFail() {
        for (var timestamp : List.of(Instant.parse("2026-09-17T00:00:00.000001Z"),
                Instant.parse("2026-09-17T16:00:00Z")))
            assertThrows(IllegalArgumentException.class, () -> mapper.fromStorage(
                    new MarketBreadthDailyCacheRow(timestamp, 1L, 0L, 0L, 0L, null, null, VERSION)));
        assertThrows(NullPointerException.class, () -> mapper.fromStorage(
                new MarketBreadthDailyCacheRow(Instant.parse("2026-09-17T00:00:00Z"), null, 0L, 0L, 0L, null, null, VERSION)));
        var values = new LinkedHashMap<>(mapper.values(cache(VERSION)).asMap());
        values.put("stock_count", 1);
        assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(values)));
        values.put("stock_count", 1L); values.remove("source_version");
        assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(values)));
    }

    @Test void malformedAggregateCountsAndNonfiniteValuesCannotEnterDomain() {
        assertThrows(IllegalArgumentException.class, () -> new MarketBreadthDailyCache(DAY, 0, 0, 0, 0, null, null, VERSION));
        assertThrows(IllegalArgumentException.class, () -> new MarketBreadthDailyCache(DAY, 1, 1, 1, 0, null, null, VERSION));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBreadthDailyCache(DAY, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0, null, null, VERSION));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBreadthDailyCache(DAY, 1, 0, 0, 0, Double.NaN, null, VERSION));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBreadthDailyCache(DAY, 1, 0, 0, 0, null, Double.NEGATIVE_INFINITY, VERSION));
    }

    @Test void tableRetainsMonthWalCompleteDedupKeyAndPythonOnlyPublication() {
        var definition = MarketBreadthDailyCacheDataset.DEFINITION;
        assertEquals("market_breadth_daily_cache", definition.datasetId());
        assertEquals(DatasetDefinition.ObjectKind.TABLE, definition.objectKind());
        assertEquals(FIELDS, definition.storageColumns());
        assertEquals(List.of("trade_date", "source_version"), definition.businessKey());
        assertEquals(definition.businessKey(), definition.dedupKey());
        assertEquals("trade_date", definition.designatedTimestamp());
        assertEquals(DatasetDefinition.Partition.MONTH, definition.partition());
        assertTrue(definition.wal());
        assertEquals(Set.of(DatasetDefinition.Capability.READ), definition.capabilities());
        assertEquals(DatasetDefinition.StorageType.SYMBOL, definition.columns().getLast().storageType());
        assertFalse(definition.columns().getLast().nullable());
        assertTrue(definition.columns().get(5).nullable()); assertTrue(definition.columns().get(6).nullable());
        assertEquals("python.MarketBarometerReadThroughCache._publish", definition.owner());
        assertThrows(IllegalArgumentException.class, () -> definition.requireCapability(DatasetDefinition.Capability.WRITE));
        assertThrows(IllegalArgumentException.class, () -> definition.requireCapability(DatasetDefinition.Capability.WAL_REPLACE));
    }

    @Test void repositoryKeyAndDatePagesPreserveCompleteSourceGeneration() {
        var reader = mock(QuestDbBoundedReader.class);
        var repository = new MarketBreadthDailyCacheReadRepository(reader);
        var captured = ArgumentCaptor.forClass(DatasetReadQuery.class);
        repository.findVersion(DAY, VERSION);
        verify(reader).read(eq(MarketBreadthDailyCacheDataset.DEFINITION), captured.capture(), isNull(), any());
        assertEquals(FIELDS, captured.getValue().columns());
        assertEquals(Map.of("trade_date", DAY, "source_version", VERSION), captured.getValue().equalities());
        assertEquals(1, captured.getValue().pageSize());
        clearInvocations(reader);
        var cursor = new DatasetReadCursor("date-query", List.of(DAY, VERSION), "cache-table-version");
        repository.findForDate(DAY, 3, cursor);
        verify(reader).read(eq(MarketBreadthDailyCacheDataset.DEFINITION), captured.capture(), isNull(), any());
        assertEquals(Map.of("trade_date", DAY), captured.getValue().equalities());
        assertEquals(3, captured.getValue().pageSize()); assertEquals(cursor, captured.getValue().cursor());
    }

    @Test void rangePagingCanExplicitlySelectOneGenerationOrReadAllWithoutSelectingLatest() {
        var reader = mock(QuestDbBoundedReader.class);
        var repository = new MarketBreadthDailyCacheReadRepository(reader);
        var captured = ArgumentCaptor.forClass(DatasetReadQuery.class);
        var cursor = new DatasetReadCursor("range-query", List.of(DAY, VERSION), "cache-table-version");
        repository.findRange(DAY, DAY.plusDays(3), VERSION, 2, cursor);
        verify(reader).read(eq(MarketBreadthDailyCacheDataset.DEFINITION), captured.capture(), isNull(), any());
        assertEquals(Map.of("source_version", VERSION), captured.getValue().equalities());
        assertEquals("trade_date", captured.getValue().rangeColumn());
        assertEquals(DAY, captured.getValue().fromInclusive()); assertEquals(DAY.plusDays(3), captured.getValue().toExclusive());
        assertEquals(2, captured.getValue().pageSize()); assertEquals(cursor, captured.getValue().cursor());
        clearInvocations(reader);
        repository.findRange(DAY, DAY.plusDays(3), 2, null);
        verify(reader).read(eq(MarketBreadthDailyCacheDataset.DEFINITION), captured.capture(), isNull(), any());
        assertTrue(captured.getValue().equalities().isEmpty());
    }

    @Test void malformedVersionRangeAndPageBudgetFailBeforeDatabaseReads() {
        var reader = mock(QuestDbBoundedReader.class);
        var repository = new MarketBreadthDailyCacheReadRepository(reader);
        assertThrows(IllegalArgumentException.class, () -> repository.findVersion(DAY, "bad-version"));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.plusDays(1), "bad-version", 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.minusDays(1), 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.plusDays(1), 0, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findForDate(DAY, 10_001, null));
        verifyNoInteractions(reader);
    }

    private static MarketBreadthDailyCache cache(String sourceVersion) {
        return new MarketBreadthDailyCache(DAY, 1, 0, 0, 0, null, null, sourceVersion);
    }
}
