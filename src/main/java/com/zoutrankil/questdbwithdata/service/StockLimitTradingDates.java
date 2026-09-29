package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.repository.ExchangeCalendarReadRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** Bounded session resolver matching Python's complete SSE master calendar; it never filters source rows by exchange/code. */
public final class StockLimitTradingDates {
    public static final int MAX_WINDOW_DAYS = 366;
    private final ExchangeCalendarReadRepository calendars;
    public StockLimitTradingDates(ExchangeCalendarReadRepository calendars) {
        this.calendars = Objects.requireNonNull(calendars);
    }
    public List<LocalDate> read(LocalDate fromInclusive, LocalDate toInclusive) {
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toInclusive);
        long days = ChronoUnit.DAYS.between(fromInclusive, toInclusive) + 1;
        if (days < 1 || days > MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("stk_limit calendar window must contain 1..366 calendar days");
        // D001 has SSE/SZSE rows but no BSE rows. Python's get_trading_dates_between uses complete SSE coverage;
        // keep that same schedule while stk_limit requests remain all-market (including .BJ codes).
        return List.copyOf(DailyTradingSessions.read(calendars, fromInclusive, toInclusive));
    }
}
