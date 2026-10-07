package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.ExchangeCalendar;
import com.zoutrankil.data.domain.ExchangeCalendarDataset;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Selects exactly covered SSE sessions; weekdays are never used as a trading-calendar substitute. */
public final class DailyTradingSessions {
    public static final int MAX_WINDOW_DAYS = 366;
    private static final int PAGE_SIZE = 500;
    private DailyTradingSessions() {}

    public static List<LocalDate> read(ExchangeCalendarReadRepository calendars,
                                      LocalDate fromInclusive, LocalDate toInclusive) {
        Objects.requireNonNull(calendars);
        Objects.requireNonNull(fromInclusive);
        Objects.requireNonNull(toInclusive);
        long dayCount = ChronoUnit.DAYS.between(fromInclusive, toInclusive) + 1;
        if (dayCount < 1 || dayCount > MAX_WINDOW_DAYS) {
            throw new IllegalArgumentException("Daily source window must contain 1..366 calendar days");
        }
        var columns = ExchangeCalendarDataset.DEFINITION.columns().stream()
                .map(com.zoutrankil.data.domain.DatasetDefinition.Column::logicalName).toList();
        var dates = new TreeMap<LocalDate, ExchangeCalendar>();
        DatasetReadCursor cursor = null;
        do {
            var query = new DatasetReadQuery(columns, Map.of("exchange", "SSE"), "calendar_date",
                    fromInclusive, toInclusive.plusDays(1), PAGE_SIZE, cursor);
            var page = calendars.findPage(query);
            for (var row : page.rows()) {
                if (!"SSE".equals(row.exchange()) || row.calendarDate().isBefore(fromInclusive)
                        || row.calendarDate().isAfter(toInclusive)
                        || dates.putIfAbsent(row.calendarDate(), row) != null) {
                    throw new IllegalStateException("Duplicate or out-of-range exchange calendar row");
                }
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        if (dates.size() != dayCount) {
            throw new IllegalStateException("Exchange calendar does not cover every requested calendar day");
        }
        return dates.values().stream().filter(ExchangeCalendar::open).map(ExchangeCalendar::calendarDate).toList();
    }

    /** Revalidates that a persisted source receipt covers every open session in a finite interval. */
    public static List<LocalDate> dates(List<ExchangeCalendar> rows, LocalDate fromInclusive, LocalDate toInclusive) {
        long dayCount = ChronoUnit.DAYS.between(fromInclusive, toInclusive) + 1;
        if (dayCount < 1 || dayCount > MAX_WINDOW_DAYS) throw new IllegalArgumentException("Invalid calendar range");
        var dates = new TreeMap<LocalDate, ExchangeCalendar>();
        for (var row : rows) {
            if (!"SSE".equals(row.exchange()) || row.calendarDate().isBefore(fromInclusive)
                    || row.calendarDate().isAfter(toInclusive) || dates.putIfAbsent(row.calendarDate(), row) != null) {
                throw new IllegalArgumentException("Invalid or duplicate SSE calendar day");
            }
        }
        if (dates.size() != dayCount) throw new IllegalArgumentException("Incomplete SSE calendar coverage");
        return dates.values().stream().filter(ExchangeCalendar::open).map(ExchangeCalendar::calendarDate).toList();
    }
}
