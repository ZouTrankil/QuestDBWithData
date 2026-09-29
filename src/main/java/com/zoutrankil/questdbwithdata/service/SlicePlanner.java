package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncSlice;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Lazy date x code x category plans; no materialization of all history or Cartesian products. */
public final class SlicePlanner {
    private SlicePlanner() {}
    public record TradingCalendar(LocalDate coveredStart, LocalDate coveredEnd, List<LocalDate> openDays, String evidence) {
        public TradingCalendar {
            if (coveredStart == null || coveredEnd == null || coveredEnd.isBefore(coveredStart)
                    || evidence == null || evidence.isBlank()) throw new IllegalArgumentException("Calendar coverage/evidence required");
            openDays = List.copyOf(openDays);
            LocalDate previous = null;
            for (var day : openDays) {
                if (day.isBefore(coveredStart) || day.isAfter(coveredEnd) || previous != null && !day.isAfter(previous)) {
                    throw new IllegalArgumentException("Calendar must contain sorted unique days within coverage");
                }
                previous = day;
            }
        }
    }
    public static Iterable<SyncSlice> windows(LocalDate start, LocalDate end, int windowDays,
                                              List<String> codes, List<String> categories, int maxSlices) {
        if (windowDays < 1) throw new IllegalArgumentException("Positive window size required");
        return dates(start, end, d -> java.time.temporal.ChronoUnit.DAYS.between(d, end) < windowDays
                ? end : d.plusDays(windowDays - 1L), false, codes, categories, maxSlices);
    }
    public static Iterable<SyncSlice> months(LocalDate start, LocalDate end, List<String> codes, List<String> categories, int maxSlices) {
        return dates(start, end, d -> YearMonth.from(d).atEndOfMonth(), true, codes, categories, maxSlices);
    }
    public static Iterable<SyncSlice> quarters(LocalDate start, LocalDate end, List<String> codes, List<String> categories, int maxSlices) {
        return dates(start, end, d -> YearMonth.of(d.getYear(), ((d.getMonthValue() - 1) / 3 + 1) * 3).atEndOfMonth(),
                true, codes, categories, maxSlices);
    }
    private static Iterable<SyncSlice> dates(LocalDate start, LocalDate end, Function<LocalDate, LocalDate> boundary,
                                            boolean period, List<String> codes, List<String> categories, int maxSlices) {
        new SyncSlice(start, end, "", "", null);
        Iterable<SyncSlice> windows = () -> new Iterator<>() {
            LocalDate current = start;
            public boolean hasNext() { return current != null; }
            public SyncSlice next() {
                if (!hasNext()) throw new NoSuchElementException();
                LocalDate naturalEnd = boundary.apply(current);
                LocalDate right = naturalEnd.isAfter(end) ? end : naturalEnd;
                var slice = new SyncSlice(current, right, "", "", period ? naturalEnd : null);
                current = right.equals(end) ? null : right.plusDays(1);
                return slice;
            }
        };
        return product(windows, codes, categories, maxSlices);
    }
    public static Iterable<SyncSlice> tradingDays(LocalDate start, LocalDate end, TradingCalendar calendar,
                                                  List<String> codes, List<String> categories, int maxSlices) {
        new SyncSlice(start, end, "", "", null);
        if (start.isBefore(calendar.coveredStart()) || end.isAfter(calendar.coveredEnd())) {
            throw new IllegalArgumentException("Trading calendar does not cover requested range");
        }
        Iterable<SyncSlice> days = () -> calendar.openDays().stream().filter(d -> !d.isBefore(start) && !d.isAfter(end))
                .map(d -> new SyncSlice(d, d, "", "", null)).iterator();
        return product(days, codes, categories, maxSlices);
    }
    private static List<String> dimension(List<String> values) {
        values = List.copyOf(values);
        if (values.size() > 100000 || new HashSet<>(values).size() != values.size()
                || values.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Invalid/duplicate dimension");
        return values.isEmpty() ? List.of("") : values;
    }
    private static Iterable<SyncSlice> product(Iterable<SyncSlice> dates, List<String> codes, List<String> categories, int maxSlices) {
        if (maxSlices < 1 || maxSlices > 1000000) throw new IllegalArgumentException("Explicit slice bound required");
        var symbols = dimension(codes);
        var groups = dimension(categories);
        long combinations = (long) symbols.size() * groups.size();
        if (combinations > maxSlices) throw new IllegalArgumentException("Slice budget exceeded before execution");
        long permittedDates = maxSlices / combinations;
        long countedDates = 0;
        for (var iterator = dates.iterator(); iterator.hasNext(); ) {
            iterator.next();
            if (++countedDates > permittedDates) {
                throw new IllegalArgumentException("Slice budget exceeded before execution");
            }
        }
        return () -> new Iterator<>() {
            final Iterator<SyncSlice> dateIterator = dates.iterator();
            SyncSlice date;
            int code, category, emitted;
            public boolean hasNext() { return date != null || dateIterator.hasNext(); }
            public SyncSlice next() {
                if (!hasNext()) throw new NoSuchElementException();
                if (++emitted > maxSlices) throw new IllegalStateException("Slice budget exceeded; plan is incomplete");
                if (date == null) date = dateIterator.next();
                var value = new SyncSlice(date.start(), date.end(), symbols.get(code), groups.get(category), date.periodEnd());
                if (++category == groups.size()) { category = 0; if (++code == symbols.size()) { code = 0; date = null; } }
                return value;
            }
        };
    }
}
