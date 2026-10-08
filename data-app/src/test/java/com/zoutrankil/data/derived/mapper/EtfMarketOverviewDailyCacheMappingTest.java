package com.zoutrankil.data.derived.mapper;

import com.zoutrankil.data.derived.storage.EtfMarketOverviewDailyCacheReadRepository;


import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.EtfMarketOverviewDailyCacheRow;
import com.zoutrankil.data.repository.*;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EtfMarketOverviewDailyCacheMappingTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 17);
    private static final String VERSION = "a".repeat(64);
    private static final List<String> FIELDS = List.of("trade_date", "etf_count", "total_share", "total_size_yi", "source_version");
    private final EtfMarketOverviewDailyCacheMapper mapper = new EtfMarketOverviewDailyCacheMapper();

    private static EtfMarketOverviewDailyCache populated() {
        return new EtfMarketOverviewDailyCache(DAY, 1774L, 12345678.123456789, 76543.987654321, VERSION);
    }
    private static EtfMarketOverviewDailyCacheRow storage(Instant date, EtfMarketOverviewDailyCache row) {
        return new EtfMarketOverviewDailyCacheRow(date, row.etfCount(), row.totalShare(), row.totalSizeYi(), row.sourceVersion());
    }

    @Test void allFiveFieldsRoundTripInCardOrderWithExactDoubleBits() {
        var row = populated(); var stored = storage(Instant.parse("2026-09-17T00:00:00Z"), row);
        assertEquals(row, mapper.fromStorage(stored)); assertEquals(row, mapper.fromValues(mapper.values(row)));
        assertEquals(FIELDS, mapper.values(row).columns().stream().toList());
        assertEquals(Double.doubleToRawLongBits(row.totalShare()), Double.doubleToRawLongBits(mapper.fromStorage(stored).totalShare()));
        assertEquals(Double.doubleToRawLongBits(row.totalSizeYi()), Double.doubleToRawLongBits(mapper.fromStorage(stored).totalSizeYi()));
        assertEquals(new EtfMarketOverviewDailyCacheKey(DAY, VERSION), row.key());
    }

    @Test void nullableAggregatesStayNullWhileRequiredZeroCountStaysZero() {
        var row = new EtfMarketOverviewDailyCache(DAY, 0, null, null, VERSION);
        assertEquals(row, mapper.fromStorage(storage(Instant.parse("2026-09-17T00:00:00Z"), row)));
        assertEquals(row, mapper.fromValues(mapper.values(row)));
        assertEquals(0L, mapper.values(row).get("etf_count", Long.class));
        assertNull(mapper.values(row).asMap().get("total_share")); assertNull(mapper.values(row).asMap().get("total_size_yi"));
        for (var field : List.of("total_share", "total_size_yi")) {
            var values = new LinkedHashMap<>(mapper.values(populated()).asMap()); values.put(field, null);
            assertEquals(values, mapper.values(mapper.fromValues(new DatasetValues(values))).asMap());
        }
    }

    @Test void historicalNullCountIsRejectedRatherThanCoercedToZero() {
        var values = new LinkedHashMap<>(mapper.values(populated()).asMap()); values.put("etf_count", null);
        assertThrows(NullPointerException.class, () -> mapper.fromValues(new DatasetValues(values)));
        assertThrows(NullPointerException.class, () -> mapper.fromStorage(new EtfMarketOverviewDailyCacheRow(
                Instant.parse("2026-09-17T00:00:00Z"), null, 10000.0, 2.0, VERSION)));
    }

    @Test void sharesAndSizeKeepOriginalTenThousandSharesAndYiWithoutAnotherConversion() {
        // Original SQL already converts 10000 ten-thousand shares * 2 yuan / 10000 to 2 yi yuan.
        var row = mapper.fromStorage(new EtfMarketOverviewDailyCacheRow(Instant.parse("2026-09-17T00:00:00Z"),
                1L, 10000.0, 2.0, VERSION));
        assertEquals(10000.0, row.totalShare()); assertEquals(2.0, row.totalSizeYi());
        assertEquals(row, mapper.fromValues(mapper.values(row)));
        assertTrue(EtfShareDataset.DEFINITION.columns().stream().filter(c -> c.logicalName().equals("fd_share"))
                .findFirst().orElseThrow().meaning().contains("ten-thousand-share"));
        assertTrue(EtfDailyDataset.DEFINITION.columns().stream().filter(c -> c.logicalName().equals("close"))
                .findFirst().orElseThrow().meaning().contains("yuan"));
        var signed = new EtfMarketOverviewDailyCache(DAY, 1, -1.25, -0.0, VERSION);
        assertEquals(signed, mapper.fromValues(mapper.values(signed)));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(mapper.fromValues(mapper.values(signed)).totalSizeYi()));
    }

    @Test void countRejectsNegativeOverflowCarriersAndCoercionsAndKeepsMaxLong() {
        for (Object invalid : List.of(-1L, Long.MIN_VALUE, Integer.valueOf(1), Double.valueOf(1.0),
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))) {
            var values = new LinkedHashMap<>(mapper.values(populated()).asMap()); values.put("etf_count", invalid);
            assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(values)));
        }
        var values = new LinkedHashMap<>(mapper.values(populated()).asMap()); values.put("etf_count", Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, mapper.fromValues(new DatasetValues(values)).etfCount());
    }

    @Test void bothDoubleFieldsRejectNonfiniteValues() {
        for (var field : List.of("total_share", "total_size_yi")) {
            for (var invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                var values = new LinkedHashMap<>(mapper.values(populated()).asMap()); values.put(field, invalid);
                assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(values)), field);
            }
        }
    }

    @Test void completeKeyKeepsDistinctDateGenerationsAndRejectsMalformedSha() {
        assertNotEquals(new EtfMarketOverviewDailyCacheKey(DAY, VERSION), new EtfMarketOverviewDailyCacheKey(DAY, "b".repeat(64)));
        assertNotEquals(new EtfMarketOverviewDailyCacheKey(DAY, VERSION), new EtfMarketOverviewDailyCacheKey(DAY.plusDays(1), VERSION));
        assertThrows(NullPointerException.class, () -> new EtfMarketOverviewDailyCacheKey(null, VERSION));
        assertThrows(IllegalArgumentException.class, () -> new EtfMarketOverviewDailyCacheKey(DAY, null));
        for (var invalid : List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), " " + VERSION, VERSION + "\n"))
            assertThrows(IllegalArgumentException.class, () -> new EtfMarketOverviewDailyCacheKey(DAY, invalid));
    }

    @Test void exactUtcCarrierAndEveryDeclaredFieldTypeAreRequired() {
        for (var timestamp : List.of(Instant.parse("2026-09-17T00:00:00.000001Z"), Instant.parse("2026-09-17T00:00:00.000000001Z"),
                Instant.parse("2026-09-17T16:00:00Z")))
            assertThrows(IllegalArgumentException.class, () -> mapper.fromStorage(storage(timestamp, populated())));
        assertThrows(NullPointerException.class, () -> mapper.fromStorage(storage(null, populated())));
        for (var field : FIELDS) {
            var missing = new LinkedHashMap<>(mapper.values(populated()).asMap()); missing.remove(field);
            assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(missing)), field);
            var wrong = new LinkedHashMap<>(mapper.values(populated()).asMap());
            Object value = field.equals("trade_date") ? "20260917" : field.equals("source_version") ? Integer.valueOf(1)
                    : field.equals("etf_count") ? (Object) Integer.valueOf(1) : Float.valueOf(1.25f);
            wrong.put(field, value); assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(wrong)), field);
        }
    }

    @Test void activeOwnerTableHasFiveFieldsCompleteKeyAndThreeOriginalSources() {
        var definition = EtfMarketOverviewDailyCacheDataset.DEFINITION;
        assertEquals("etf_market_overview_daily_cache", definition.datasetId()); assertEquals(FIELDS, definition.storageColumns());
        assertEquals(DatasetDefinition.ObjectKind.TABLE, definition.objectKind()); assertEquals(DatasetDefinition.Partition.MONTH, definition.partition());
        assertTrue(definition.wal()); assertEquals("trade_date", definition.designatedTimestamp());
        assertEquals(List.of("trade_date", "source_version"), definition.businessKey()); assertEquals(definition.businessKey(), definition.dedupKey());
        assertEquals(List.of("etf_share", "etf_daily", "etf_basic"), definition.dependencies());
        assertEquals("python.MarketBarometerReadThroughCache.read", definition.owner());
        assertEquals(Set.of(DatasetDefinition.Capability.READ, DatasetDefinition.Capability.WRITE), definition.capabilities());
        for (var column : definition.columns()) {
            assertEquals(column.storageName(), column.sourceName());
            assertEquals(Set.of("total_share", "total_size_yi").contains(column.storageName()), column.nullable());
        }
        assertEquals("BASIC", definition.columns().getFirst().temporal().sourceFormat());
        assertEquals("calendar", definition.columns().getFirst().temporal().zone());
        assertEquals("DAY", definition.columns().getFirst().temporal().precision());
        assertEquals(DatasetDefinition.StorageType.LONG, definition.columns().get(1).storageType());
        assertEquals(DatasetDefinition.StorageType.SYMBOL, definition.columns().getLast().storageType());
        assertDoesNotThrow(() -> definition.requireCapability(DatasetDefinition.Capability.WRITE));
        for (var capability : List.of(DatasetDefinition.Capability.STATIC_REPLACE, DatasetDefinition.Capability.WAL_REPLACE))
            assertThrows(IllegalArgumentException.class, () -> definition.requireCapability(capability));
    }

    @Test void repositoryPreservesCompleteKeyAndBusinessVersionSeparateFromPhysicalCursor() {
        var reader = mock(QuestDbBoundedReader.class); var repository = new EtfMarketOverviewDailyCacheReadRepository(reader);
        var queries = ArgumentCaptor.forClass(DatasetReadQuery.class);
        repository.findKey(new EtfMarketOverviewDailyCacheKey(DAY, VERSION));
        verify(reader).read(eq(EtfMarketOverviewDailyCacheDataset.DEFINITION), queries.capture(), isNull(), any());
        assertEquals(FIELDS, queries.getValue().columns()); assertEquals(Map.of("trade_date", DAY, "source_version", VERSION), queries.getValue().equalities());
        assertEquals(1, queries.getValue().pageSize());
        clearInvocations(reader); repository.findVersion(DAY, VERSION);
        verify(reader).read(eq(EtfMarketOverviewDailyCacheDataset.DEFINITION), queries.capture(), isNull(), any());
        assertEquals(Map.of("trade_date", DAY, "source_version", VERSION), queries.getValue().equalities());
        var cursor = new DatasetReadCursor("actual-query-fingerprint", List.of(DAY, VERSION), "physical-cache-version");
        clearInvocations(reader); repository.findForDate(DAY, 2, cursor);
        verify(reader).read(eq(EtfMarketOverviewDailyCacheDataset.DEFINITION), queries.capture(), isNull(), any());
        assertEquals(Map.of("trade_date", DAY), queries.getValue().equalities()); assertEquals(cursor, queries.getValue().cursor());
        clearInvocations(reader); repository.findRange(DAY, DAY.plusDays(5), VERSION, 2, cursor);
        verify(reader).read(eq(EtfMarketOverviewDailyCacheDataset.DEFINITION), queries.capture(), isNull(), any());
        assertEquals(Map.of("source_version", VERSION), queries.getValue().equalities()); assertEquals(cursor, queries.getValue().cursor());
        assertEquals("trade_date", queries.getValue().rangeColumn()); assertEquals(DAY, queries.getValue().fromInclusive());
        assertEquals(DAY.plusDays(5), queries.getValue().toExclusive());
        clearInvocations(reader); repository.findRange(DAY, DAY.plusDays(5), 2, null);
        verify(reader).read(eq(EtfMarketOverviewDailyCacheDataset.DEFINITION), queries.capture(), isNull(), any());
        assertTrue(queries.getValue().equalities().isEmpty());
    }

    @Test void invalidKeyDateVersionRangeAndBudgetFailBeforeReading() {
        var reader = mock(QuestDbBoundedReader.class); var repository = new EtfMarketOverviewDailyCacheReadRepository(reader);
        assertThrows(NullPointerException.class, () -> repository.findKey(null));
        assertThrows(NullPointerException.class, () -> repository.findForDate(null, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findVersion(DAY, "bad"));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.plusDays(1), "bad", 1, null));
        assertThrows(NullPointerException.class, () -> repository.findRange(null, DAY, 1, null));
        assertThrows(NullPointerException.class, () -> repository.findRange(DAY, null, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.minusDays(1), 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.plusDays(1), 0, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findForDate(DAY, 10001, null));
        verifyNoInteractions(reader);
    }
}
