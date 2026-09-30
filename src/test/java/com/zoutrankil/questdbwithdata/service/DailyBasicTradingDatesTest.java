package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.ExchangeCalendarReadRepository;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyBasicTradingDatesTest {
    @Test void unionsOpenDatesAcrossBothFullyCoveredExchanges() {
        var rows = List.of(
                new ExchangeCalendar("SSE", LocalDate.of(2026, 9, 26), false, null),
                new ExchangeCalendar("SZSE", LocalDate.of(2026, 9, 26), false, null),
                new ExchangeCalendar("SSE", LocalDate.of(2026, 9, 27), true, null),
                new ExchangeCalendar("SZSE", LocalDate.of(2026, 9, 27), true, null),
                new ExchangeCalendar("SSE", LocalDate.of(2026, 9, 28), true, null),
                new ExchangeCalendar("SZSE", LocalDate.of(2026, 9, 28), false, null));
        var service = new DailyBasicTradingDates(calendar(rows));
        assertEquals(List.of(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 28)),
                service.read(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 28)));
    }

    @Test void incompleteCalendarWindowIsNotTreatedAsAClosedMarket() {
        var rows = List.of(new ExchangeCalendar("SSE", LocalDate.of(2026, 9, 28), true, null));
        var service = new DailyBasicTradingDates(calendar(rows));
        assertThrows(IllegalStateException.class,
                () -> service.read(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 28)));
    }

    private static ExchangeCalendarReadRepository calendar(List<ExchangeCalendar> rows) {
        return new ExchangeCalendarReadRepository(null) {
            @Override public DatasetReadPage<ExchangeCalendar> findPage(DatasetReadQuery query) {
                assertTrue(query.columns().containsAll(List.of("exchange", "calendar_date", "is_open", "previous_trade_date")),
                        "The typed calendar mapper requires all calendar columns, including nullable previous_trade_date");
                return new DatasetReadPage<>("exchange_calendar", 1, null, Instant.now(), rows, null);
            }
        };
    }
}
