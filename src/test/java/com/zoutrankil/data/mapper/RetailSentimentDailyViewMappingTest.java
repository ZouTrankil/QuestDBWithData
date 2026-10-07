package com.zoutrankil.data.mapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.data.domain.*;

import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.repository.RetailSentimentDailyViewReadRepository;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RetailSentimentDailyViewMappingTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 17);
    private static final List<String> FIELDS = List.of("trade_date", "avg_retail_ratio", "avg_retail_entropy",
            "total_retail_amount_yi", "total_retail_net_inflow_yi", "avg_rel_aggro", "total_q1", "total_q3",
            "avg_wash_trade_ratio", "total_spoof_count", "total_manipulation_count", "avg_mfi_score", "total_main_net_yi");
    private static final Set<String> COUNT_FIELDS = Set.of("total_q1", "total_q3", "total_spoof_count", "total_manipulation_count");
    private final RetailSentimentDailyViewMapper mapper = new RetailSentimentDailyViewMapper();

    private RetailSentimentDailyView populated() {
        return new RetailSentimentDailyView(DAY, 0.23123456789012345, 2.1532465789012346,
                53.123456789012345, -4.234567890123456, 0.5312345678901234, 101L, 202L,
                0.07123456789012345, 303L, 404L, 1.2512345678901234, -9.876543210123456);
    }

    private RetailSentimentDailyView emptyAggregates() {
        return new RetailSentimentDailyView(DAY, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    @Test void everyPhysicalFieldHasAnExplicitLosslessMappingInCardOrder() {
        var expected = populated();
        var stored = new com.zoutrankil.data.domain.view.RetailSentimentDailyView(Instant.parse("2026-09-17T00:00:00Z"),
                expected.avgRetailRatio(), expected.avgRetailEntropy(), expected.totalRetailAmountYi(),
                expected.totalRetailNetInflowYi(), expected.avgRelAggro(), expected.totalQ1(), expected.totalQ3(),
                expected.avgWashTradeRatio(), expected.totalSpoofCount(), expected.totalManipulationCount(),
                expected.avgMfiScore(), expected.totalMainNetYi());
        assertEquals(expected, mapper.fromStorage(stored));
        assertEquals(expected, mapper.fromValues(mapper.values(expected)));
        assertEquals(FIELDS, mapper.values(expected).columns().stream().toList());
        var values = mapper.values(mapper.fromStorage(stored));
        for (var field : FIELDS.subList(1, FIELDS.size())) {
            if (COUNT_FIELDS.contains(field)) {
                assertEquals(mapper.values(expected).get(field, Long.class), values.get(field, Long.class));
            } else {
                assertEquals(Double.doubleToLongBits(mapper.values(expected).get(field, Double.class)),
                        Double.doubleToLongBits(values.get(field, Double.class)));
            }
        }
    }

    @Test void allNullableSumAndAverageFieldsStayNullRatherThanBecomingZero() {
        var stored = new com.zoutrankil.data.domain.view.RetailSentimentDailyView(Instant.parse("2026-09-17T00:00:00Z"),
                null, null, null, null, null, null, null, null, null, null, null, null);
        var row = mapper.fromStorage(stored);
        assertEquals(emptyAggregates(), row);
        assertEquals(row, mapper.fromValues(mapper.values(row)));
        for (var field : FIELDS.subList(1, FIELDS.size())) assertNull(mapper.values(row).asMap().get(field));
        var zeroValues = new LinkedHashMap<>(mapper.values(row).asMap());
        COUNT_FIELDS.forEach(field -> zeroValues.put(field, 0L));
        var zeroCounts = mapper.fromValues(new DatasetValues(zeroValues));
        assertNotEquals(row, zeroCounts);
        assertEquals(0L, zeroCounts.totalManipulationCount());
    }

    @Test void aliasAggregateUnitsAndSignedFlowsPassThroughWithoutExtraScalingOrClamping() {
        var values = new LinkedHashMap<>(mapper.values(populated()).asMap());
        values.put("total_retail_amount_yi", -123.0);
        values.put("avg_retail_ratio", 1.25);
        values.put("avg_wash_trade_ratio", -0.125);
        values.put("avg_rel_aggro", 2.0);
        values.put("avg_mfi_score", 3.5);
        var row = mapper.fromValues(new DatasetValues(values));
        assertEquals(-123.0, row.totalRetailAmountYi());
        assertEquals(populated().totalRetailNetInflowYi(), row.totalRetailNetInflowYi());
        assertEquals(populated().totalMainNetYi(), row.totalMainNetYi());
        assertEquals(1.25, row.avgRetailRatio());
        assertEquals(-0.125, row.avgWashTradeRatio());
        assertEquals(2.0, row.avgRelAggro());
        assertEquals(3.5, row.avgMfiScore());
    }

    @Test void nullableLongCountsRejectNegativeOrOverflowedValuesAndRetainExactMaxLong() {
        for (var field : COUNT_FIELDS) {
            for (var invalid : List.of(-1L, Long.MIN_VALUE)) {
                var values = new LinkedHashMap<>(mapper.values(populated()).asMap());
                values.put(field, invalid);
                assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(values)), field);
            }
        }
        var maximum = new LinkedHashMap<>(mapper.values(populated()).asMap());
        COUNT_FIELDS.forEach(field -> maximum.put(field, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, mapper.fromValues(new DatasetValues(maximum)).totalManipulationCount());
        maximum.put("total_q1", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(maximum)));
    }

    @Test void everyDoubleAggregateRejectsNanAndEitherInfinity() {
        for (var field : FIELDS.subList(1, FIELDS.size())) {
            if (COUNT_FIELDS.contains(field)) continue;
            for (var invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                var values = new LinkedHashMap<>(mapper.values(populated()).asMap());
                values.put(field, invalid);
                assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(values)), field);
            }
        }
    }

    @Test void onlyExactUtcMidnightIsAcceptedAsTheCalendarDateCarrier() {
        for (var carrier : List.of(Instant.parse("2026-09-17T00:00:00.000001Z"),
                Instant.parse("2026-09-17T16:00:00Z"), Instant.parse("2026-09-16T16:00:00Z"))) {
            assertThrows(IllegalArgumentException.class, () -> mapper.fromStorage(new com.zoutrankil.data.domain.view.RetailSentimentDailyView(
                    carrier, null, null, null, null, null, null, null, null, null, null, null, null)));
        }
        assertThrows(NullPointerException.class, () -> mapper.fromStorage(new com.zoutrankil.data.domain.view.RetailSentimentDailyView(
                null, null, null, null, null, null, null, null, null, null, null, null, null)));
        var noDate = new LinkedHashMap<>(mapper.values(emptyAggregates()).asMap());
        noDate.put("trade_date", null);
        assertThrows(NullPointerException.class, () -> mapper.fromValues(new DatasetValues(noDate)));
    }

    @Test void absentFieldsAndCoercedNumericOrDateTypesAreRejected() {
        for (var field : FIELDS) {
            var missing = new LinkedHashMap<>(mapper.values(populated()).asMap());
            missing.remove(field);
            assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(missing)), field);
            var wrong = new LinkedHashMap<>(mapper.values(populated()).asMap());
            Object invalid = field.equals("trade_date") ? Instant.parse("2026-09-17T00:00:00Z")
                    : COUNT_FIELDS.contains(field) ? (Object) Integer.valueOf(1) : Float.valueOf(0.5f);
            wrong.put(field, invalid);
            assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(wrong)), field);
        }
    }

    @Test void aliasDefinitionHasThirteenExplicitParentColumnsAndNoPhysicalWriteContract() {
        var definition = RetailSentimentDailyViewDataset.DEFINITION;
        assertEquals("v_retail_sentiment_daily", definition.datasetId());
        assertEquals(definition.datasetId(), definition.objectName());
        assertEquals(DatasetDefinition.ObjectKind.VIEW, definition.objectKind());
        assertEquals(FIELDS, definition.storageColumns());
        assertEquals(List.of("trade_date"), definition.businessKey());
        assertTrue(definition.dedupKey().isEmpty());
        assertEquals("trade_date", definition.designatedTimestamp());
        assertEquals(DatasetDefinition.Partition.NONE, definition.partition());
        assertFalse(definition.wal());
        assertEquals(List.of("mv_retail_sentiment_daily_v1"), definition.dependencies());
        assertEquals(Set.of(DatasetDefinition.Capability.READ), definition.capabilities());
        for (var capability : List.of(DatasetDefinition.Capability.WRITE, DatasetDefinition.Capability.STATIC_REPLACE,
                DatasetDefinition.Capability.WAL_REPLACE)) {
            assertThrows(IllegalArgumentException.class, () -> definition.requireCapability(capability));
        }
        var date = definition.columns().getFirst();
        assertEquals("mv_retail_sentiment_daily_v1.trade_date", date.sourceName());
        assertFalse(date.nullable());
        assertEquals(DatasetDefinition.TemporalKind.BUSINESS_DATE, date.temporal().kind());
        assertEquals("calendar", date.temporal().zone());
        assertEquals("DAY", date.temporal().precision());
        for (var field : definition.columns().subList(1, definition.columns().size())) {
            assertTrue(field.nullable(), field.storageName());
            assertEquals(field.storageName(), field.logicalName());
            assertEquals(COUNT_FIELDS.contains(field.storageName()) ? DatasetDefinition.StorageType.LONG
                    : DatasetDefinition.StorageType.DOUBLE, field.storageType());
        }
        for (var field : definition.columns()) {
            assertEquals("mv_retail_sentiment_daily_v1." + field.storageName(), field.sourceName());
        }
    }

    @Test void parentAndAliasKeepAllThirteenFieldValuesAndNullableCountTypesIdentical() {
        var parentMapper = new RetailSentimentDailyV1Mapper();
        for (var alias : List.of(populated(), emptyAggregates())) {
            var parent = parentMapper.fromValues(mapper.values(alias));
            assertEquals(mapper.values(alias).asMap(), parentMapper.values(parent).asMap());
            assertEquals(alias, mapper.fromValues(parentMapper.values(parent)));
        }
        var parentDefinition = RetailSentimentDailyV1Dataset.DEFINITION;
        var aliasDefinition = RetailSentimentDailyViewDataset.DEFINITION;
        for (int index = 0; index < FIELDS.size(); index++) {
            var parent = parentDefinition.columns().get(index);
            var alias = aliasDefinition.columns().get(index);
            assertEquals(parent.storageName(), alias.storageName());
            assertEquals(parent.storageType(), alias.storageType());
            assertEquals(parent.nullable(), alias.nullable());
        }
    }

    @Test void repositoryForwardsCompleteDateKeyAndFiniteExclusiveRangeAndTheCursor() {
        var reader = mock(QuestDbBoundedReader.class);
        var repository = new RetailSentimentDailyViewReadRepository(reader);
        var queries = ArgumentCaptor.forClass(DatasetReadQuery.class);
        repository.findForDate(DAY);
        verify(reader).read(eq(RetailSentimentDailyViewDataset.DEFINITION), queries.capture(), isNull(), any());
        assertEquals(FIELDS, queries.getValue().columns());
        assertEquals(Map.of("trade_date", DAY), queries.getValue().equalities());
        assertEquals(1, queries.getValue().pageSize());
        assertNull(queries.getValue().rangeColumn());
        clearInvocations(reader);
        var cursor = new DatasetReadCursor("retail-query", List.of(DAY), "frozen-source-mv-physical-version");
        repository.findRange(DAY, DAY.plusDays(5), 2, cursor);
        verify(reader).read(eq(RetailSentimentDailyViewDataset.DEFINITION), queries.capture(), isNull(), any());
        var range = queries.getValue();
        assertEquals(FIELDS, range.columns());
        assertTrue(range.equalities().isEmpty());
        assertEquals("trade_date", range.rangeColumn());
        assertEquals(DAY, range.fromInclusive());
        assertEquals(DAY.plusDays(5), range.toExclusive());
        assertEquals(2, range.pageSize());
        assertEquals(cursor, range.cursor());
    }

    @Test void repositoryRejectsInvalidDatesRangesAndPageBudgetsBeforeReads() {
        var reader = mock(QuestDbBoundedReader.class);
        var repository = new RetailSentimentDailyViewReadRepository(reader);
        assertThrows(NullPointerException.class, () -> repository.findForDate(null));
        assertThrows(NullPointerException.class, () -> repository.findRange(null, DAY, 1, null));
        assertThrows(NullPointerException.class, () -> repository.findRange(DAY, null, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.minusDays(1), 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.plusDays(1), 0, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.plusDays(1), 10_001, null));
        verifyNoInteractions(reader);
    }
}
