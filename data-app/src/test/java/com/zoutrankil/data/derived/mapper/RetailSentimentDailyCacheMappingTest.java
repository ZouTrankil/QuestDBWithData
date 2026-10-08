package com.zoutrankil.data.derived.mapper;

import com.zoutrankil.data.derived.storage.RetailSentimentDailyCacheReadRepository;


import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.RetailSentimentDailyCacheRow;
import com.zoutrankil.data.repository.*;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RetailSentimentDailyCacheMappingTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 17);
    private static final String VERSION = "a".repeat(64);
    private static final List<String> FIELDS = List.of("trade_date", "avg_retail_ratio", "avg_retail_entropy",
            "total_retail_amount_yi", "total_retail_net_inflow_yi", "avg_rel_aggro", "total_q1", "total_q3",
            "avg_wash_trade_ratio", "total_spoof_count", "total_manipulation_count", "avg_mfi_score", "total_main_net_yi", "source_version");
    private static final Set<String> COUNTS = Set.of("total_q1", "total_q3", "total_spoof_count", "total_manipulation_count");
    private final RetailSentimentDailyCacheMapper mapper = new RetailSentimentDailyCacheMapper();

    private static RetailSentimentDailyCache populated() {
        return new RetailSentimentDailyCache(DAY, 0.23123456789012345, 2.1532465789012346,
                53.123456789012345, -4.234567890123456, 0.5312345678901234, 101L, 202L,
                0.07123456789012345, 303L, 404L, 1.2512345678901234, -9.876543210123456, VERSION);
    }
    private static RetailSentimentDailyCacheRow storage(Instant date, RetailSentimentDailyCache row) {
        return new RetailSentimentDailyCacheRow(date, row.avgRetailRatio(), row.avgRetailEntropy(), row.totalRetailAmountYi(),
                row.totalRetailNetInflowYi(), row.avgRelAggro(), row.totalQ1(), row.totalQ3(), row.avgWashTradeRatio(),
                row.totalSpoofCount(), row.totalManipulationCount(), row.avgMfiScore(), row.totalMainNetYi(), row.sourceVersion());
    }

    @Test void allFourteenFieldsRoundTripInCardOrderWithExactDoubleBits() {
        var row = populated(); var stored = storage(Instant.parse("2026-09-17T00:00:00Z"), row);
        assertEquals(row, mapper.fromStorage(stored)); assertEquals(row, mapper.fromValues(mapper.values(row)));
        assertEquals(FIELDS, mapper.values(row).columns().stream().toList());
        for (var field : FIELDS.subList(1, 13)) if (!COUNTS.contains(field)) {
            assertEquals(Double.doubleToRawLongBits(mapper.values(row).get(field, Double.class)),
                    Double.doubleToRawLongBits(mapper.values(mapper.fromStorage(stored)).get(field, Double.class)));
        }
        assertEquals(new RetailSentimentDailyCacheKey(DAY, VERSION), row.key());
    }

    @Test void nullableDoublesRemainNullAndRequiredZeroCountsRemainZero() {
        var row = new RetailSentimentDailyCache(DAY, null, null, null, null, null, 0, 0, null, 0, 0, null, null, VERSION);
        assertEquals(row, mapper.fromStorage(storage(Instant.parse("2026-09-17T00:00:00Z"), row)));
        assertEquals(row, mapper.fromValues(mapper.values(row)));
        for (var field : FIELDS.subList(1, 13)) {
            if (COUNTS.contains(field)) assertEquals(0L, mapper.values(row).get(field, Long.class));
            else assertNull(mapper.values(row).asMap().get(field));
        }
    }

    @Test void historicalNullCountsAreExplicitlyInvalidRatherThanFilledWithZero() {
        for (var field : COUNTS) {
            var values = new LinkedHashMap<>(mapper.values(populated()).asMap()); values.put(field, null);
            assertThrows(NullPointerException.class, () -> mapper.fromValues(new DatasetValues(values)), field);
            var row = populated();
            var stored = new RetailSentimentDailyCacheRow(Instant.parse("2026-09-17T00:00:00Z"), row.avgRetailRatio(),
                    row.avgRetailEntropy(), row.totalRetailAmountYi(), row.totalRetailNetInflowYi(), row.avgRelAggro(),
                    field.equals("total_q1") ? null : row.totalQ1(), field.equals("total_q3") ? null : row.totalQ3(),
                    row.avgWashTradeRatio(), field.equals("total_spoof_count") ? null : row.totalSpoofCount(),
                    field.equals("total_manipulation_count") ? null : row.totalManipulationCount(), row.avgMfiScore(), row.totalMainNetYi(), VERSION);
            assertThrows(NullPointerException.class, () -> mapper.fromStorage(stored), field);
        }
    }

    @Test void signedUnitsAndSourceMetricScalesPassThroughWithoutConversionOrClamp() {
        var values = new LinkedHashMap<>(mapper.values(populated()).asMap());
        values.put("total_retail_amount_yi", -123.0); values.put("avg_retail_ratio", 1.25);
        values.put("avg_wash_trade_ratio", -0.125); values.put("avg_rel_aggro", 2.0); values.put("avg_mfi_score", 3.5);
        var row = mapper.fromValues(new DatasetValues(values));
        assertEquals(-123.0, row.totalRetailAmountYi()); assertEquals(1.25, row.avgRetailRatio());
        assertEquals(-0.125, row.avgWashTradeRatio()); assertEquals(2.0, row.avgRelAggro()); assertEquals(3.5, row.avgMfiScore());
        assertEquals(populated().totalMainNetYi(), row.totalMainNetYi());
        assertEquals(populated().totalRetailNetInflowYi(), row.totalRetailNetInflowYi());
    }

    @Test void countsRejectNegativeWrappedOrCoercedNumbersAndKeepMaxLongExactly() {
        for (var field : COUNTS) {
            for (var invalid : List.of(-1L, Long.MIN_VALUE, Integer.valueOf(1), BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))) {
                var values = new LinkedHashMap<>(mapper.values(populated()).asMap()); values.put(field, invalid);
                assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(values)), field);
            }
        }
        var values = new LinkedHashMap<>(mapper.values(populated()).asMap()); COUNTS.forEach(field -> values.put(field, Long.MAX_VALUE));
        for (var field : COUNTS) assertEquals(Long.MAX_VALUE, mapper.values(mapper.fromValues(new DatasetValues(values))).get(field, Long.class));
    }

    @Test void allDoubleFieldsRejectNonfiniteValues() {
        for (var field : FIELDS.subList(1, 13)) if (!COUNTS.contains(field)) {
            for (var invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                var values = new LinkedHashMap<>(mapper.values(populated()).asMap()); values.put(field, invalid);
                assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(values)), field);
            }
        }
    }

    @Test void completeIdentityPreservesDistinctGenerationsAndRejectsMalformedSha() {
        assertNotEquals(new RetailSentimentDailyCacheKey(DAY, VERSION), new RetailSentimentDailyCacheKey(DAY, "b".repeat(64)));
        assertThrows(NullPointerException.class, () -> new RetailSentimentDailyCacheKey(null, VERSION));
        assertThrows(IllegalArgumentException.class, () -> new RetailSentimentDailyCacheKey(DAY, null));
        for (var invalid : List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), " " + VERSION, VERSION + "\n"))
            assertThrows(IllegalArgumentException.class, () -> new RetailSentimentDailyCacheKey(DAY, invalid));
    }

    @Test void dateCarrierAndEveryMissingOrWrongFieldFailWithoutCoercion() {
        for (var instant : List.of(Instant.parse("2026-09-17T00:00:00.000001Z"), Instant.parse("2026-09-17T16:00:00Z")))
            assertThrows(IllegalArgumentException.class, () -> mapper.fromStorage(storage(instant, populated())));
        assertThrows(NullPointerException.class, () -> mapper.fromStorage(storage(null, populated())));
        for (var field : FIELDS) {
            var missing = new LinkedHashMap<>(mapper.values(populated()).asMap()); missing.remove(field);
            assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(missing)), field);
            var wrong = new LinkedHashMap<>(mapper.values(populated()).asMap());
            Object value = field.equals("trade_date") ? Instant.parse("2026-09-17T00:00:00Z")
                    : field.equals("source_version") ? Integer.valueOf(1)
                    : COUNTS.contains(field) ? (Object) Integer.valueOf(1) : Float.valueOf(0.5f);
            wrong.put(field, value); assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(wrong)), field);
        }
    }

    @Test void tableRetainsFourteenFieldsMonthWalCompleteDedupKeyAndSolePythonOwner() {
        var definition = RetailSentimentDailyCacheDataset.DEFINITION;
        assertEquals("retail_sentiment_daily_cache", definition.datasetId());
        assertEquals(DatasetDefinition.ObjectKind.TABLE, definition.objectKind()); assertEquals(FIELDS, definition.storageColumns());
        assertEquals(List.of("trade_date", "source_version"), definition.businessKey()); assertEquals(definition.businessKey(), definition.dedupKey());
        assertEquals("trade_date", definition.designatedTimestamp()); assertEquals(DatasetDefinition.Partition.MONTH, definition.partition());
        assertTrue(definition.wal()); assertTrue(definition.dependencies().isEmpty());
        assertEquals("python.MarketBarometerReadThroughCache._publish", definition.owner());
        assertEquals(Set.of(DatasetDefinition.Capability.READ), definition.capabilities());
        for (var column : definition.columns()) {
            assertEquals(column.storageName(), column.sourceName());
            assertEquals(!COUNTS.contains(column.storageName()) && !Set.of("trade_date", "source_version").contains(column.storageName()), column.nullable());
        }
        assertEquals(DatasetDefinition.StorageType.SYMBOL, definition.columns().getLast().storageType());
        for (var capability : List.of(DatasetDefinition.Capability.WRITE, DatasetDefinition.Capability.STATIC_REPLACE, DatasetDefinition.Capability.WAL_REPLACE))
            assertThrows(IllegalArgumentException.class, () -> definition.requireCapability(capability));
    }

    @Test void repositoryPreservesKeyBusinessGenerationFiniteRangeAndPhysicalCursorSeparately() {
        var reader = mock(QuestDbBoundedReader.class); var repository = new RetailSentimentDailyCacheReadRepository(reader);
        var queries = ArgumentCaptor.forClass(DatasetReadQuery.class);
        repository.findKey(new RetailSentimentDailyCacheKey(DAY, VERSION));
        verify(reader).read(eq(RetailSentimentDailyCacheDataset.DEFINITION), queries.capture(), isNull(), any());
        assertEquals(FIELDS, queries.getValue().columns()); assertEquals(Map.of("trade_date", DAY, "source_version", VERSION), queries.getValue().equalities());
        assertEquals(1, queries.getValue().pageSize());
        var cursor = new DatasetReadCursor("cache-range", List.of(DAY, VERSION), "physical-table-frontier");
        clearInvocations(reader); repository.findRange(DAY, DAY.plusDays(5), VERSION, 2, cursor);
        verify(reader).read(eq(RetailSentimentDailyCacheDataset.DEFINITION), queries.capture(), isNull(), any());
        assertEquals(Map.of("source_version", VERSION), queries.getValue().equalities()); assertEquals(cursor, queries.getValue().cursor());
        assertEquals("trade_date", queries.getValue().rangeColumn()); assertEquals(DAY.plusDays(5), queries.getValue().toExclusive());
        clearInvocations(reader); repository.findForDate(DAY, 2, cursor);
        verify(reader).read(eq(RetailSentimentDailyCacheDataset.DEFINITION), queries.capture(), isNull(), any());
        assertEquals(Map.of("trade_date", DAY), queries.getValue().equalities());
        clearInvocations(reader); repository.findRange(DAY, DAY.plusDays(5), 2, null);
        verify(reader).read(eq(RetailSentimentDailyCacheDataset.DEFINITION), queries.capture(), isNull(), any()); assertTrue(queries.getValue().equalities().isEmpty());
    }

    @Test void invalidKeyVersionDateRangeAndPageBudgetAreRejectedBeforeDatabaseRead() {
        var reader = mock(QuestDbBoundedReader.class); var repository = new RetailSentimentDailyCacheReadRepository(reader);
        assertThrows(NullPointerException.class, () -> repository.findKey(null));
        assertThrows(NullPointerException.class, () -> repository.findForDate(null, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findVersion(DAY, "bad"));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.plusDays(1), "bad", 1, null));
        assertThrows(NullPointerException.class, () -> repository.findRange(null, DAY, 1, null));
        assertThrows(NullPointerException.class, () -> repository.findRange(DAY, null, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.minusDays(1), 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.plusDays(1), 0, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findForDate(DAY, 10001, null)); verifyNoInteractions(reader);
    }
}
