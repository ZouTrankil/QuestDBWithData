package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.repository.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.MacroCoreMonthlyViewMapper;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MacroCoreMonthlyViewReadRepositoryTest {
    private static final YearMonth MONTH = YearMonth.of(2026, 6);
    private static final DatasetDefinition DEFINITION = MacroCoreMonthlyViewDataset.DEFINITION;

    @Test void fullMonthKeyAndConvenienceUseFirstDayEqualityAndReaderOwnedPhysicalVersion() {
        var reader = mock(QuestDbBoundedReader.class); var repo = new MacroCoreMonthlyViewReadRepository(reader);
        assertEquals(DEFINITION, repo.definition()); var query = ArgumentCaptor.forClass(DatasetReadQuery.class);
        repo.findKey(new MacroCoreMonthlyViewKey(MONTH));
        verify(reader).read(eq(DEFINITION), query.capture(), isNull(), any());
        assertEquals(DEFINITION.storageColumns(), query.getValue().columns());
        assertEquals(Map.of("month", MONTH.atDay(1)), query.getValue().equalities()); assertEquals(1, query.getValue().pageSize());
        assertNull(query.getValue().rangeColumn()); assertNull(query.getValue().fromInclusive());
        assertNull(query.getValue().toExclusive()); assertNull(query.getValue().cursor());
        clearInvocations(reader); repo.findForMonth(MONTH);
        verify(reader).read(eq(DEFINITION), query.capture(), isNull(), any());
        assertEquals(Map.of("month", MONTH.atDay(1)), query.getValue().equalities());
    }

    @Test void halfOpenMonthRangeAndActualViewBaseCursorPassThroughWithoutExpansion() {
        var reader = mock(QuestDbBoundedReader.class); var repo = new MacroCoreMonthlyViewReadRepository(reader);
        var cursor = new DatasetReadCursor("real-query", List.of(MONTH.atDay(1)), "actual-view-base-vector");
        var query = ArgumentCaptor.forClass(DatasetReadQuery.class);
        repo.findRange(MONTH, MONTH.plusMonths(3), 1, cursor);
        verify(reader).read(eq(DEFINITION), query.capture(), isNull(), any());
        assertEquals("month", query.getValue().rangeColumn()); assertTrue(query.getValue().equalities().isEmpty());
        assertEquals(MONTH.atDay(1), query.getValue().fromInclusive());
        assertEquals(MONTH.plusMonths(3).atDay(1), query.getValue().toExclusive());
        assertEquals(1, query.getValue().pageSize()); assertSame(cursor, query.getValue().cursor());
    }

    @Test void businessDecoderPreservesAllNullFieldsSignedZeroAndActualReaderSnapshotToken() {
        var reader = mock(QuestDbBoundedReader.class); var repo = new MacroCoreMonthlyViewReadRepository(reader);
        var values = new LinkedHashMap<String, Object>(); values.put("month", MONTH.atDay(1));
        DEFINITION.storageColumns().subList(1, 9).forEach(field -> values.put(field, null)); values.put("gdp_yoy", -0.0);
        var expected = new MacroCoreMonthlyViewMapper().fromValues(values);
        doAnswer(call -> {
            Function<DatasetValues, MacroCoreMonthlyView> decode = call.getArgument(3);
            return new DatasetReadPage<>(DEFINITION.datasetId(), 1, "actual-view-base-version", Instant.EPOCH,
                    List.of(decode.apply(new DatasetValues(values))), null);
        }).when(reader).read(eq(DEFINITION), any(), isNull(), any());
        var page = repo.findForMonth(MONTH);
        assertEquals(List.of(expected), page.rows()); assertEquals("actual-view-base-version", page.sourceVersion());
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(page.rows().getFirst().gdpYoy()));
    }

    @Test void invalidWindowWrongCarrierPartialFieldsOrPageBudgetRejectBeforeAnyReaderCall() {
        var reader = mock(QuestDbBoundedReader.class); var repo = new MacroCoreMonthlyViewReadRepository(reader);
        assertThrows(NullPointerException.class, () -> repo.findKey(null));
        assertThrows(NullPointerException.class, () -> repo.findForMonth(null));
        assertThrows(NullPointerException.class, () -> repo.findPage(null));
        assertThrows(NullPointerException.class, () -> repo.findRange(null, MONTH, 1, null));
        assertThrows(NullPointerException.class, () -> repo.findRange(MONTH, null, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repo.findRange(MONTH, MONTH, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repo.findRange(MONTH, MONTH.minusMonths(1), 1, null));
        assertThrows(IllegalArgumentException.class, () -> repo.findRange(MONTH, MONTH.plusMonths(13), 1, null));
        assertThrows(IllegalArgumentException.class, () -> repo.findRange(MONTH, MONTH.plusMonths(1), 13, null));
        assertThrows(IllegalArgumentException.class, () -> repo.findRange(MONTH, MONTH.plusMonths(1), 0, null));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), null, null, null, 1, null)));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of("month", MONTH), null, null, null, 1, null)));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of("month", MONTH.atDay(2)), null, null, null, 1, null)));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(List.of("month"), Map.of("month", MONTH.atDay(1)), null, null, null, 1, null)));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of("month", MONTH.atDay(1)), "month", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1), 1, null)));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), "gdp_yoy", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1), 1, null)));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), "month", MONTH, MONTH.plusMonths(1), 1, null)));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), "month", MONTH.atDay(2), MONTH.plusMonths(1).atDay(1), 1, null)));
        verifyNoInteractions(reader);
    }

    @Test void exactlyTwelveMonthsAndRowsArePermittedAtTheFiniteBoundary() {
        var reader = mock(QuestDbBoundedReader.class); var repo = new MacroCoreMonthlyViewReadRepository(reader);
        var query = ArgumentCaptor.forClass(DatasetReadQuery.class);
        repo.findRange(MONTH, MONTH.plusMonths(12), 12, null);
        verify(reader).read(eq(DEFINITION), query.capture(), isNull(), any());
        assertEquals(MONTH.plusMonths(12).atDay(1), query.getValue().toExclusive()); assertEquals(12, query.getValue().pageSize());
    }

    @Test void changedViewOrBaseFailurePropagatesWithoutFallbackToAnEmptyOrBasePage() {
        var reader = mock(QuestDbBoundedReader.class); var repo = new MacroCoreMonthlyViewReadRepository(reader);
        var failure = new IllegalStateException("Actual view SQL or base version changed");
        doThrow(failure).when(reader).read(eq(DEFINITION), any(), isNull(), any());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> repo.findForMonth(MONTH)));
        verify(reader).read(eq(DEFINITION), any(), isNull(), any()); verifyNoMoreInteractions(reader);
    }

    @Test void explicitPrivateAliasPreservesLogicalDependencyAndRejectsUnapprovedObject() {
        var reader = mock(QuestDbBoundedReader.class);
        var repo = new MacroCoreMonthlyViewReadRepository(reader, MacroCoreMonthlyViewDataset.ISOLATED_OBJECT);
        assertEquals(MacroCoreMonthlyViewDataset.definition(MacroCoreMonthlyViewDataset.ISOLATED_OBJECT), repo.definition());
        assertEquals(MacroCoreMonthlyViewDataset.ISOLATED_OBJECT, repo.definition().objectName());
        assertEquals("v_macro_core_monthly", repo.definition().datasetId());
        assertEquals(List.of("macro_core_monthly"), repo.definition().dependencies());
        for (String object : List.of("macro_core_monthly", "unrelated", "java_d105_v_macro_core_monthly_other", "v_macro_core_monthly WHERE true"))
            assertThrows(IllegalArgumentException.class, () -> new MacroCoreMonthlyViewReadRepository(reader, object));
        assertThrows(NullPointerException.class, () -> new MacroCoreMonthlyViewReadRepository(null));
        verifyNoInteractions(reader);
    }

    @Test void emptyActualViewPageKeepsItsPhysicalTokenAndDoesNotInvokeAnotherObject() {
        var reader = mock(QuestDbBoundedReader.class); var repo = new MacroCoreMonthlyViewReadRepository(reader);
        var expected = new DatasetReadPage<MacroCoreMonthlyView>(DEFINITION.datasetId(), 1, "actual-empty-view-base-version",
                Instant.EPOCH, List.of(), null);
        doReturn(expected).when(reader).read(eq(DEFINITION), any(), isNull(), any());
        var actual = repo.findForMonth(MONTH); assertSame(expected, actual);
        assertTrue(actual.rows().isEmpty()); assertFalse(actual.hasMore());
        assertEquals("actual-empty-view-base-version", actual.sourceVersion());
        verify(reader).read(eq(DEFINITION), any(), isNull(), any()); verifyNoMoreInteractions(reader);
    }
}
