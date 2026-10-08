package com.zoutrankil.batch;

import java.time.*;
import java.util.*;

/** Explicit version and coverage: weekdays are not a substitute for a trading calendar. */
public record TradingCalendar(String version, LocalDate start, LocalDate end, NavigableSet<LocalDate> openDays) {
    public TradingCalendar {
        Objects.requireNonNull(version); Objects.requireNonNull(start); Objects.requireNonNull(end);
        if (version.isBlank() || start.isAfter(end)) throw new IllegalArgumentException("Invalid calendar");
        openDays = Collections.unmodifiableNavigableSet(new TreeSet<>(openDays));
        if (openDays.stream().anyMatch(d -> d.isBefore(start) || d.isAfter(end)))
            throw new IllegalArgumentException("Open date outside calendar coverage");
    }
    public boolean isOpen(LocalDate date) { requireCoverage(date); return openDays.contains(date); }
    public LocalDate previousOrSame(LocalDate date) {
        requireCoverage(date);
        LocalDate result = openDays.floor(date);
        if (result == null) throw new IllegalArgumentException("Calendar has no preceding open date");
        return result;
    }
    public boolean isMonthEnd(LocalDate date) {
        requireCoverage(YearMonth.from(date).atEndOfMonth());
        LocalDate next = openDays.higher(date);
        return isOpen(date) && (next == null || !YearMonth.from(next).equals(YearMonth.from(date)));
    }
    private void requireCoverage(LocalDate date) {
        if (date.isBefore(start) || date.isAfter(end)) throw new IllegalArgumentException("Unknown calendar coverage");
    }
}
