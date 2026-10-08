package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.service.DailyTradingSessions;

import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** Bounded session resolver matching Python's complete SSE master calendar; it never filters source rows by exchange/code. */
public final class EtfShareTradingDates {
    public static final int MAX_WINDOW_DAYS = 366;
    private final ExchangeCalendarReadRepository calendars;
    public EtfShareTradingDates(ExchangeCalendarReadRepository calendars) {
        this.calendars = Objects.requireNonNull(calendars);
    }
    public List<LocalDate> read(LocalDate fromInclusive, LocalDate toInclusive) {
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toInclusive);
        long days = ChronoUnit.DAYS.between(fromInclusive, toInclusive) + 1;
        if (days < 1 || days > MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("etf_share calendar window must contain 1..366 calendar days");
        // Match Python fund_share scheduling using the complete SSE master calendar; no source-code filtering.
        return List.copyOf(DailyTradingSessions.read(calendars, fromInclusive, toInclusive));
    }
}
