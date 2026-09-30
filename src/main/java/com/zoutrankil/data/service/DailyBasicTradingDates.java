package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.ExchangeCalendarReadRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Resolves a fully covered SSE/SZSE calendar window; daily_basic is not gated by daily rows. */
public final class DailyBasicTradingDates {
    private static final List<String> EXCHANGES = List.of("SSE", "SZSE");
    private final ExchangeCalendarReadRepository calendar;
    public DailyBasicTradingDates(ExchangeCalendarReadRepository calendar) {
        this.calendar = Objects.requireNonNull(calendar);
    }

    public List<LocalDate> read(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (from.isAfter(to) || days > 366) throw new IllegalArgumentException("daily_basic calendar window must be 1..366 days");
        var columns = ExchangeCalendarDataset.DEFINITION.columns().stream()
                .map(DatasetDefinition.Column::logicalName).toList();
        var query = new DatasetReadQuery(columns, Map.of(), "calendar_date", from, to.plusDays(1), 1000, null);
        var calendarRows = new ArrayList<ExchangeCalendar>();
        while (true) {
            var page = calendar.findPage(query);
            calendarRows.addAll(page.rows());
            if (!page.hasMore()) break;
            query = query.after(page.nextCursor());
        }
        int expected = Math.toIntExact(Math.multiplyExact(days, EXCHANGES.size()));
        if (calendarRows.size() != expected)
            throw new IllegalStateException("SSE/SZSE calendar does not cover every date in the requested daily_basic window");
        var exchangesByDate = new HashMap<LocalDate, Set<String>>();
        var openDates = new TreeSet<LocalDate>();
        for (var row : calendarRows) {
            if (row.calendarDate().isBefore(from) || row.calendarDate().isAfter(to)
                    || !EXCHANGES.contains(row.exchange()))
                throw new IllegalStateException("Calendar row outside the frozen daily_basic window");
            if (!exchangesByDate.computeIfAbsent(row.calendarDate(), ignored -> new HashSet<>()).add(row.exchange()))
                throw new IllegalStateException("Duplicate exchange calendar identity in daily_basic window");
            if (row.open()) openDates.add(row.calendarDate());
        }
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (!exchangesByDate.getOrDefault(date, Set.of()).containsAll(EXCHANGES))
                throw new IllegalStateException("Missing an exchange calendar row for " + date);
        }
        return List.copyOf(openDates);
    }
}
