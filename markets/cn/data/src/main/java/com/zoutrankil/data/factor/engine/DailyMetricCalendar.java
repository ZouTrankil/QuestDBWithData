package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** Adjacent official SSE sessions; every calendar date in the requested interval must exist. */
public final class DailyMetricCalendar {
    private DailyMetricCalendar() {}

    public record Day(String exchange, LocalDate date, boolean open) {}

    public record Pair(LocalDate signal, LocalDate maturity) {}

    public static List<Pair> maturePairs(List<Day> calendar, LocalDate start, LocalDate asOf) {
        if (start == null
                || asOf == null
                || start.isAfter(asOf)
                || ChronoUnit.DAYS.between(start, asOf) > 3660)
            throw new IllegalArgumentException("Bounded daily metric date range required");
        var days = new TreeMap<LocalDate, Boolean>();
        for (var day : calendar)
            if ("SSE".equals(day.exchange())) {
                Boolean prior = days.putIfAbsent(Objects.requireNonNull(day.date()), day.open());
                if (prior != null && prior != day.open())
                    throw new IllegalArgumentException("Conflicting official calendar day");
            }
        var result = new ArrayList<Pair>();
        LocalDate previous = null;
        for (LocalDate date = start; !date.isAfter(asOf); date = date.plusDays(1)) {
            Boolean open = days.get(date);
            if (open == null)
                throw new IllegalArgumentException("Official calendar coverage missing: " + date);
            if (open) {
                if (previous != null) result.add(new Pair(previous, date));
                previous = date;
            }
        }
        return List.copyOf(result);
    }
}
