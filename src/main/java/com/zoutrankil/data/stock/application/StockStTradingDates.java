package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** D001-backed D012 calendar resolver; no weekday or exchange-holiday inference is allowed. */
public final class StockStTradingDates {
    public static final int MAX_WINDOW_DAYS = 366;
    private final ExchangeCalendarReadRepository calendars;
    public StockStTradingDates(ExchangeCalendarReadRepository calendars) { this.calendars = Objects.requireNonNull(calendars); }
    public List<LocalDate> read(LocalDate fromInclusive, LocalDate toInclusive) {
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toInclusive);
        long days = ChronoUnit.DAYS.between(fromInclusive, toInclusive) + 1;
        if (days < 1 || days > MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("stk_st_daily window must contain 1..366 calendar days");
        return List.copyOf(DailyTradingSessions.read(calendars, fromInclusive, toInclusive));
    }
}
