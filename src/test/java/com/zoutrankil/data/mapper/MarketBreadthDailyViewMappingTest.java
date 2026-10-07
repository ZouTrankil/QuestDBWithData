package com.zoutrankil.data.mapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.MarketBreadthDailyViewReadRepository;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MarketBreadthDailyViewMappingTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 17);
    private static final List<String> FIELDS = List.of("trade_date", "stock_count", "up_count", "down_count",
            "flat_count", "avg_pct_change", "total_amount_yi");
    private final MarketBreadthDailyViewMapper mapper = new MarketBreadthDailyViewMapper();

    @Test void allSevenFieldsPassThroughWithExactDateAndAggregateUnits() {
        var source = new com.zoutrankil.data.domain.view.MarketBreadthDailyView(
                Instant.parse("2026-09-17T00:00:00Z"), 5552L, 2575L, 2820L, 157L,
                0.12166565201729085, 18343.331240500058);
        var expected = new MarketBreadthDailyView(DAY, 5552, 2575, 2820, 157,
                source.avgPctChange(), source.totalAmountYi());
        var row = mapper.fromStorage(source);
        assertEquals(expected, row);
        assertEquals(expected, mapper.fromValues(mapper.values(row)));
        assertEquals(FIELDS, mapper.values(row).columns().stream().toList());
        assertEquals(Double.doubleToLongBits(source.avgPctChange()), Double.doubleToLongBits(row.avgPctChange()));
        assertEquals(Double.doubleToLongBits(source.totalAmountYi()), Double.doubleToLongBits(row.totalAmountYi()));
    }

    @Test void nullAggregatesStayNullAndNullChangeRowsAreNotCountedAsFlat() {
        var row = mapper.fromStorage(new com.zoutrankil.data.domain.view.MarketBreadthDailyView(
                Instant.parse("2026-09-17T00:00:00Z"), 2L, 0L, 0L, 0L, null, null));
        assertEquals(2, row.stockCount());
        assertEquals(0, row.flatCount());
        assertNull(row.avgPctChange()); assertNull(row.totalAmountYi());
        assertEquals(row, mapper.fromValues(mapper.values(row)));
    }

    @Test void nonMidnightCarriersMissingRequiredFieldsAndWrongTypesAreRejected() {
        for (var carrier : List.of(Instant.parse("2026-09-17T00:00:00.000001Z"),
                Instant.parse("2026-09-17T16:00:00Z"))) {
            assertThrows(IllegalArgumentException.class, () -> mapper.fromStorage(
                    new com.zoutrankil.data.domain.view.MarketBreadthDailyView(carrier, 1L, 0L, 0L, 0L, null, null)));
        }
        assertThrows(NullPointerException.class, () -> mapper.fromStorage(
                new com.zoutrankil.data.domain.view.MarketBreadthDailyView(
                        Instant.parse("2026-09-17T00:00:00Z"), null, 0L, 0L, 0L, null, null)));
        var values = new LinkedHashMap<>(mapper.values(new MarketBreadthDailyView(DAY, 1, 0, 0, 0, null, null)).asMap());
        values.put("stock_count", 1);
        assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(values)));
        values.put("stock_count", 1L); values.remove("total_amount_yi");
        assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(values)));
    }

    @Test void invalidCountsIncludingOverflowAndNonfiniteAggregatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MarketBreadthDailyView(DAY, 0, 0, 0, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new MarketBreadthDailyView(DAY, 1, -1, 0, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new MarketBreadthDailyView(DAY, 1, 1, 1, 0, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBreadthDailyView(DAY, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBreadthDailyView(DAY, 1, 0, 0, 0, Double.NaN, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBreadthDailyView(DAY, 1, 0, 0, 0, null, Double.POSITIVE_INFINITY));
    }

    @Test void ordinaryAliasContractHasIndependentExplicitBindingAndRejectsDirectWrites() {
        var definition = MarketBreadthDailyViewDataset.DEFINITION;
        assertEquals("v_market_breadth_daily", definition.datasetId());
        assertEquals("v_market_breadth_daily", definition.objectName());
        assertEquals(DatasetDefinition.ObjectKind.VIEW, definition.objectKind());
        assertEquals(List.of("trade_date"), definition.businessKey());
        assertEquals("trade_date", definition.designatedTimestamp());
        assertTrue(definition.dedupKey().isEmpty());
        assertEquals(DatasetDefinition.Partition.NONE, definition.partition());
        assertFalse(definition.wal());
        assertEquals(Set.of(DatasetDefinition.Capability.READ), definition.capabilities());
        assertEquals(List.of("mv_market_breadth_daily_v1"), definition.dependencies());
        assertEquals(FIELDS, definition.storageColumns());
        for (var column : definition.columns()) {
            assertEquals("mv_market_breadth_daily_v1." + column.storageName(), column.sourceName());
            assertEquals(column.storageName(), column.logicalName());
            assertEquals(Set.of("avg_pct_change", "total_amount_yi").contains(column.storageName()), column.nullable());
        }
        assertEquals(DatasetDefinition.TemporalKind.BUSINESS_DATE, definition.columns().getFirst().temporal().kind());
        assertThrows(IllegalArgumentException.class, () -> definition.requireCapability(DatasetDefinition.Capability.WRITE));
        assertThrows(IllegalArgumentException.class, () -> definition.requireCapability(DatasetDefinition.Capability.STATIC_REPLACE));
        assertThrows(IllegalArgumentException.class, () -> definition.requireCapability(DatasetDefinition.Capability.WAL_REPLACE));
    }

    @Test void repositoryForwardsExactKeyAndExclusiveRangeWithBoundedPageAndCursor() {
        var reader = mock(QuestDbBoundedReader.class);
        var repository = new MarketBreadthDailyViewReadRepository(reader);
        var query = ArgumentCaptor.forClass(DatasetReadQuery.class);
        repository.findForDate(DAY);
        verify(reader).read(eq(MarketBreadthDailyViewDataset.DEFINITION), query.capture(), isNull(), any());
        assertEquals(FIELDS, query.getValue().columns());
        assertEquals(java.util.Map.of("trade_date", DAY), query.getValue().equalities());
        assertEquals(1, query.getValue().pageSize());
        clearInvocations(reader);
        var cursor = new DatasetReadCursor("alias-query", List.of(DAY), "base-and-mv-version");
        repository.findRange(DAY, DAY.plusDays(3), 2, cursor);
        verify(reader).read(eq(MarketBreadthDailyViewDataset.DEFINITION), query.capture(), isNull(), any());
        assertEquals(FIELDS, query.getValue().columns());
        assertEquals("trade_date", query.getValue().rangeColumn());
        assertEquals(DAY, query.getValue().fromInclusive());
        assertEquals(DAY.plusDays(3), query.getValue().toExclusive());
        assertEquals(2, query.getValue().pageSize()); assertEquals(cursor, query.getValue().cursor());
    }

    @Test void repositoryRejectsInvalidRangesAndPageBudgetsBeforeDatabaseReads() {
        var reader = mock(QuestDbBoundedReader.class);
        var repository = new MarketBreadthDailyViewReadRepository(reader);
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.minusDays(1), 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.plusDays(1), 0, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.plusDays(1), 10_001, null));
        assertThrows(NullPointerException.class, () -> repository.findForDate(null));
        verifyNoInteractions(reader);
    }
}
