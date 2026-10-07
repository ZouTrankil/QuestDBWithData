package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.MacroCoreMonthlyMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MacroCoreMonthlyReadRepositoryTest {
    private static final YearMonth MONTH = YearMonth.of(2026, 6);
    private static final DatasetDefinition DEFINITION = MacroCoreMonthlyDataset.DEFINITION;
    @Test void keyAndConvenienceReadUseFirstDayEqualityAndDelegatePhysicalCursorPin() {
        var reader = mock(QuestDbBoundedReader.class); var repo = new MacroCoreMonthlyReadRepository(reader);
        assertEquals(DEFINITION, repo.definition());
        var query = ArgumentCaptor.forClass(DatasetReadQuery.class);
        repo.findKey(new MacroCoreMonthlyKey(MONTH));
        verify(reader).read(eq(DEFINITION), query.capture(), isNull(), any());
        assertEquals(DEFINITION.storageColumns(), query.getValue().columns());
        assertEquals(Map.of("month", MONTH.atDay(1)), query.getValue().equalities()); assertEquals(1, query.getValue().pageSize());
        assertNull(query.getValue().rangeColumn()); assertNull(query.getValue().cursor());
        clearInvocations(reader); repo.findForMonth(MONTH);
        verify(reader).read(eq(DEFINITION), query.capture(), isNull(), any());
        assertEquals(Map.of("month", MONTH.atDay(1)), query.getValue().equalities());
    }
    @Test void halfOpenMonthRangeKeepsLocalDateBoundsAndActualPhysicalCursor() {
        var reader = mock(QuestDbBoundedReader.class); var repo = new MacroCoreMonthlyReadRepository(reader);
        var cursor = new DatasetReadCursor("real-query", List.of(MONTH.atDay(1)), "actual-table-vector");
        var query = ArgumentCaptor.forClass(DatasetReadQuery.class);
        repo.findRange(MONTH, MONTH.plusMonths(3), 1, cursor);
        verify(reader).read(eq(DEFINITION), query.capture(), isNull(), any());
        assertEquals("month", query.getValue().rangeColumn()); assertTrue(query.getValue().equalities().isEmpty());
        assertEquals(MONTH.atDay(1), query.getValue().fromInclusive());
        assertEquals(MONTH.plusMonths(3).atDay(1), query.getValue().toExclusive()); assertSame(cursor, query.getValue().cursor());
    }
    @Test void completeDecoderRetainsNullableValuesAndReadersPhysicalToken() {
        var reader = mock(QuestDbBoundedReader.class); var repo = new MacroCoreMonthlyReadRepository(reader);
        var values = new LinkedHashMap<String,Object>(); values.put("month", MONTH.atDay(1));
        DEFINITION.storageColumns().subList(1,9).forEach(f -> values.put(f, null)); values.put("gdp_yoy", -0.0);
        var expected = new MacroCoreMonthlyMapper().fromValues(values);
        doAnswer(call -> {
            Function<DatasetValues,MacroCoreMonthly> decode = call.getArgument(3);
            return new DatasetReadPage<>(DEFINITION.datasetId(), 1, "actual-physical-generation", Instant.EPOCH,
                    List.of(decode.apply(new DatasetValues(values))), null);
        }).when(reader).read(eq(DEFINITION), any(), isNull(), any());
        var result = repo.findForMonth(MONTH);
        assertEquals(List.of(expected), result.rows()); assertEquals("actual-physical-generation", result.sourceVersion());
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(result.rows().getFirst().gdpYoy()));
    }
    @Test void invalidUnboundedWrongCarrierPartialFieldsOrBudgetRejectBeforeReader() {
        var reader = mock(QuestDbBoundedReader.class); var repo = new MacroCoreMonthlyReadRepository(reader);
        assertThrows(NullPointerException.class, () -> repo.findKey(null));
        assertThrows(NullPointerException.class, () -> repo.findForMonth(null));
        assertThrows(NullPointerException.class, () -> repo.findPage(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findRange(MONTH,MONTH,1,null));
        assertThrows(IllegalArgumentException.class, () -> repo.findRange(MONTH,MONTH.minusMonths(1),1,null));
        assertThrows(IllegalArgumentException.class, () -> repo.findRange(MONTH,MONTH.plusMonths(13),1,null));
        assertThrows(IllegalArgumentException.class, () -> repo.findRange(MONTH,MONTH.plusMonths(1),13,null));
        assertThrows(IllegalArgumentException.class, () -> repo.findRange(MONTH,MONTH.plusMonths(1),0,null));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(
                DEFINITION.storageColumns(), Map.of(), null,null,null,1,null)));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(
                DEFINITION.storageColumns(), Map.of("month",MONTH), null,null,null,1,null)));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(
                DEFINITION.storageColumns(), Map.of("month",MONTH.atDay(2)), null,null,null,1,null)));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(
                List.of("month"), Map.of("month",MONTH.atDay(1)), null,null,null,1,null)));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(
                DEFINITION.storageColumns(), Map.of("month",MONTH.atDay(1)), "month",MONTH.atDay(1),MONTH.plusMonths(1).atDay(1),1,null)));
        verifyNoInteractions(reader);
    }
    @Test void exactlyTwelveMonthsAndRowsArePermittedWithoutExpandingRange() {
        var reader = mock(QuestDbBoundedReader.class); var repo = new MacroCoreMonthlyReadRepository(reader);
        var query = ArgumentCaptor.forClass(DatasetReadQuery.class);
        repo.findRange(MONTH,MONTH.plusMonths(12),12,null);
        verify(reader).read(eq(DEFINITION),query.capture(),isNull(),any());
        assertEquals(MONTH.plusMonths(12).atDay(1),query.getValue().toExclusive()); assertEquals(12,query.getValue().pageSize());
    }
    @Test void physicalVersionDriftFailurePropagatesInsteadOfBecomingEmpty() {
        var reader = mock(QuestDbBoundedReader.class); var repo = new MacroCoreMonthlyReadRepository(reader);
        var error = new IllegalStateException("Actual output snapshot changed");
        doThrow(error).when(reader).read(eq(DEFINITION),any(),isNull(),any());
        assertSame(error,assertThrows(IllegalStateException.class,()->repo.findForMonth(MONTH)));
    }
    @Test void explicitIsolatedTargetUsesSameDefinitionAndArbitraryObjectIsRejected() {
        var reader = mock(QuestDbBoundedReader.class); String table="java_d104_macro_core_monthly_acceptance";
        var repo = new MacroCoreMonthlyReadRepository(reader,table);
        assertEquals(MacroCoreMonthlyDataset.definition(table),repo.definition());
        assertEquals(table,repo.definition().objectName()); assertEquals("macro_core_monthly",repo.definition().datasetId());
        assertThrows(IllegalArgumentException.class,()->new MacroCoreMonthlyReadRepository(reader,"unrelated"));
        assertThrows(NullPointerException.class,()->new MacroCoreMonthlyReadRepository(null));
        verifyNoInteractions(reader);
    }
}

