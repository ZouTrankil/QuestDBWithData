package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.ExchangeCalendar;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyTradingSessionsTest {
    @Test void filtersSessionsOnlyAfterExactFullCalendarCoverage() {
        var from = LocalDate.of(2026, 9, 26);
        var rows = List.of(
                new ExchangeCalendar("SSE", from, false, from.minusDays(1)),
                new ExchangeCalendar("SSE", from.plusDays(1), false, from.minusDays(1)),
                new ExchangeCalendar("SSE", from.plusDays(2), true, from.minusDays(1)));
        assertEquals(List.of(from.plusDays(2)), DailyTradingSessions.dates(rows, from, from.plusDays(2)));
        assertThrows(IllegalArgumentException.class, () -> DailyTradingSessions.dates(rows.subList(0, 2), from, from.plusDays(2)));
        assertThrows(IllegalArgumentException.class, () -> DailyTradingSessions.dates(List.of(rows.get(0), rows.get(0), rows.get(2)), from, from.plusDays(2)));
    }

    @Test void sourceDateCeilingMatchesShanghaiCompletionCutoff() {
        var beforeCutoff = ZonedDateTime.of(2026, 9, 29, 20, 29, 59, 0, ZoneId.of("Asia/Shanghai"));
        var atCutoff = ZonedDateTime.of(2026, 9, 29, 20, 30, 0, 0, ZoneId.of("Asia/Shanghai"));
        assertEquals(LocalDate.of(2026, 9, 28), DailySyncEndDate.resolve(null, beforeCutoff));
        assertEquals(LocalDate.of(2026, 9, 29), DailySyncEndDate.resolve(null, atCutoff));
        assertEquals(LocalDate.of(2026, 9, 28), DailySyncEndDate.resolve(LocalDate.of(2026, 9, 28), atCutoff));
    }
}
