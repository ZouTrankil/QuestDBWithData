package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.repository.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.EtfMarketOverviewDailyViewMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EtfMarketOverviewDailyViewReadRepositoryTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 17);
    private static final DatasetDefinition DEFINITION = EtfMarketOverviewDailyViewDataset.DEFINITION;

    @Test void completeDateKeyAndDateConvenienceUseExactEqualityWithoutAQueryRangeOrGeneration() {
        var reader = mock(QuestDbBoundedReader.class); var repository = new EtfMarketOverviewDailyViewReadRepository(reader);
        assertEquals(DEFINITION, repository.definition());
        var query = ArgumentCaptor.forClass(DatasetReadQuery.class);
        repository.findKey(new EtfMarketOverviewDailyViewKey(DAY));
        verify(reader).read(eq(DEFINITION), query.capture(), isNull(), any());
        assertEquals(DEFINITION.storageColumns(), query.getValue().columns());
        assertEquals(Map.of("trade_date", DAY), query.getValue().equalities());
        assertNull(query.getValue().rangeColumn()); assertNull(query.getValue().fromInclusive());
        assertNull(query.getValue().toExclusive()); assertNull(query.getValue().cursor());
        assertEquals(1, query.getValue().pageSize());
        clearInvocations(reader); repository.findForDate(DAY);
        verify(reader).read(eq(DEFINITION), query.capture(), isNull(), any());
        assertEquals(Map.of("trade_date", DAY), query.getValue().equalities());
    }

    @Test void explicitHalfOpenRangeAndPhysicalVectorCursorPassThroughToTheSharedReader() {
        var reader = mock(QuestDbBoundedReader.class); var repository = new EtfMarketOverviewDailyViewReadRepository(reader);
        var cursor = new DatasetReadCursor("query-fingerprint", List.of(DAY), "actual-share-daily-vector");
        var query = ArgumentCaptor.forClass(DatasetReadQuery.class);
        repository.findRange(DAY, DAY.plusDays(5), 2, cursor);
        verify(reader).read(eq(DEFINITION), query.capture(), isNull(), any());
        assertEquals(DEFINITION.storageColumns(), query.getValue().columns()); assertTrue(query.getValue().equalities().isEmpty());
        assertEquals("trade_date", query.getValue().rangeColumn()); assertEquals(DAY, query.getValue().fromInclusive());
        assertEquals(DAY.plusDays(5), query.getValue().toExclusive()); assertEquals(2, query.getValue().pageSize());
        assertSame(cursor, query.getValue().cursor());
    }

    @Test void completeExplicitQueryUsesTheBusinessMapperAndPreservesTheReadersActualSourceToken() {
        var reader = mock(QuestDbBoundedReader.class); var repository = new EtfMarketOverviewDailyViewReadRepository(reader);
        var expected = new EtfMarketOverviewDailyView(DAY, 1L, null, -0.0);
        var values = new EtfMarketOverviewDailyViewMapper().values(expected);
        var query = new DatasetReadQuery(DEFINITION.storageColumns(), Map.of("trade_date", DAY), null, null, null, 1, null);
        doAnswer(call -> {
            Function<DatasetValues, EtfMarketOverviewDailyView> decode = call.getArgument(3);
            return new DatasetReadPage<>(DEFINITION.datasetId(), 1, "actual-view-and-bases-vector", Instant.EPOCH,
                    List.of(decode.apply(values)), null);
        }).when(reader).read(eq(DEFINITION), eq(query), isNull(), any());
        var page = repository.findPage(query);
        assertEquals(List.of(expected), page.rows()); assertEquals("actual-view-and-bases-vector", page.sourceVersion());
        assertNull(page.nextCursor()); assertFalse(page.hasMore());
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(page.rows().getFirst().totalSizeYi()));
    }

    @Test void invalidDateRangePageBudgetOrMissingQueryFailBeforeReaderInteraction() {
        var reader = mock(QuestDbBoundedReader.class); var repository = new EtfMarketOverviewDailyViewReadRepository(reader);
        assertThrows(NullPointerException.class, () -> repository.findKey(null));
        assertThrows(NullPointerException.class, () -> repository.findForDate(null));
        assertThrows(NullPointerException.class, () -> repository.findPage(null));
        assertThrows(NullPointerException.class, () -> repository.findRange(null, DAY, 1, null));
        assertThrows(NullPointerException.class, () -> repository.findRange(DAY, null, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.minusDays(1), 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.plusDays(1), 0, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.plusDays(1), 32, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.plusDays(1), 10001, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(DAY, DAY.plusDays(32), 1, null));
        assertThrows(IllegalArgumentException.class, () -> repository.findPage(new DatasetReadQuery(
                DEFINITION.storageColumns(), Map.of("trade_date", DAY), null, null, null, 32, null)));
        verifyNoInteractions(reader);
    }

    @Test void exactlyThirtyOneCalendarDaysAndRowsArePermittedWithoutExpandingTheQuery() {
        var reader = mock(QuestDbBoundedReader.class); var repository = new EtfMarketOverviewDailyViewReadRepository(reader);
        var query = ArgumentCaptor.forClass(DatasetReadQuery.class);
        repository.findRange(DAY, DAY.plusDays(31), 31, null);
        verify(reader).read(eq(DEFINITION), query.capture(), isNull(), any());
        assertEquals(DAY.plusDays(31), query.getValue().toExclusive());
        assertEquals(31, query.getValue().pageSize());
    }

    @Test void unsettledOrChangedSourceFailurePropagatesInsteadOfBecomingAnEmptyPage() {
        var reader = mock(QuestDbBoundedReader.class); var repository = new EtfMarketOverviewDailyViewReadRepository(reader);
        var failure = new IllegalStateException("Pinned view or source version changed");
        doThrow(failure).when(reader).read(eq(DEFINITION), any(), isNull(), any());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> repository.findForDate(DAY)));
    }

    @Test void theRepositoryRequiresItsSharedReaderAndCreatesNoIndependentPublisher() {
        assertThrows(NullPointerException.class, () -> new EtfMarketOverviewDailyViewReadRepository(null));
        assertEquals(List.of("etf_share", "etf_daily"), DEFINITION.dependencies());
        assertFalse(DEFINITION.capabilities().contains(DatasetDefinition.Capability.WRITE));
    }
}
