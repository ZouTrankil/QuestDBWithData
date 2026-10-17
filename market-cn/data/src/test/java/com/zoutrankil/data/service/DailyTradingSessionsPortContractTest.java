package com.zoutrankil.data.service;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.domain.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DailyTradingSessionsPortContractTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @Test void pagedPortPreservesColumnsFiltersWindowAndCursorBeforeFilteringClosedDays() {
        var cursor = new DatasetReadCursor("frozen-query", List.of(DAY), "same-generation");
        var queries = new ArrayList<DatasetReadQuery>();
        ExchangeCalendarReadPort port = query -> {
            queries.add(query);
            return query.cursor() == null
                    ? page(List.of(row(DAY, false)), cursor)
                    : page(List.of(row(DAY.plusDays(1), true), row(DAY.plusDays(2), true)), null);
        };
        assertEquals(List.of(DAY.plusDays(1), DAY.plusDays(2)), DailyTradingSessions.read(port, DAY, DAY.plusDays(2)));
        assertEquals(2, queries.size());
        assertEquals(new DatasetReadQuery(ExchangeCalendarDataset.DEFINITION.columns().stream()
                .map(DatasetDefinition.Column::logicalName).toList(), Map.of("exchange", "SSE"),
                "calendar_date", DAY, DAY.plusDays(3), 500, null), queries.getFirst());
        assertSame(cursor, queries.getLast().cursor());
    }

    @Test void legacyRepositoryEntryDelegatesTheSameReadContract() {
        var repository = mock(ExchangeCalendarReadRepository.class);
        var result = page(List.of(row(DAY, false), row(DAY.plusDays(1), true)), null);
        when(repository.findPage(any())).thenReturn(result);
        assertEquals(DailyTradingSessions.read((ExchangeCalendarReadPort) query -> result, DAY, DAY.plusDays(1)),
                DailyTradingSessions.read(repository, DAY, DAY.plusDays(1)));
        verify(repository, times(1)).findPage(any());
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "duplicate", "foreign", "before", "after"})
    void malformedOrIncompleteCalendarFailsBeforeReturningSessions(String defect) {
        List<ExchangeCalendar> rows = switch (defect) {
            case "missing" -> List.of(row(DAY, true));
            case "duplicate" -> List.of(row(DAY, true), row(DAY, true));
            case "foreign" -> List.of(new ExchangeCalendar("SZSE", DAY, true, null));
            case "before" -> List.of(row(DAY.minusDays(1), true));
            case "after" -> List.of(row(DAY.plusDays(2), true));
            default -> throw new AssertionError(defect);
        };
        ExchangeCalendarReadPort port = query -> page(rows, null);
        assertThrows(IllegalStateException.class, () -> DailyTradingSessions.read(port, DAY, DAY.plusDays(1)));
    }

    @Test void invalidWindowOrNullPortFailsBeforeAnyRead() {
        var port = mock(ExchangeCalendarReadPort.class);
        assertThrows(IllegalArgumentException.class, () -> DailyTradingSessions.read(port, DAY, DAY.minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> DailyTradingSessions.read(port, DAY, DAY.plusDays(366)));
        assertThrows(NullPointerException.class, () -> DailyTradingSessions.read((ExchangeCalendarReadPort) null, DAY, DAY));
        assertThrows(NullPointerException.class, () -> DailyTradingSessions.read((ExchangeCalendarReadRepository) null, DAY, DAY));
        verifyNoInteractions(port);
    }

    private static ExchangeCalendar row(LocalDate day, boolean open) {
        return new ExchangeCalendar("SSE", day, open, null);
    }
    private static DatasetReadPage<ExchangeCalendar> page(List<ExchangeCalendar> rows, DatasetReadCursor cursor) {
        return new DatasetReadPage<>("exchange_calendar", 1, null, Instant.EPOCH, rows, cursor);
    }
}
