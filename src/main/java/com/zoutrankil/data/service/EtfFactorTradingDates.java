package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.ExchangeCalendarReadRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** Resolves a finite, frozen exchange-calendar slice for daily all-market factor calls. */
public final class EtfFactorTradingDates {
    public static final int MAX_WINDOW_DAYS = EtfFactorSyncJobOwner.MAX_WINDOW_DAYS;
    private final ExchangeCalendarReadRepository calendars;
    public EtfFactorTradingDates(ExchangeCalendarReadRepository calendars) { this.calendars = Objects.requireNonNull(calendars); }
    public List<LocalDate> read(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days < 1 || days > MAX_WINDOW_DAYS) throw new IllegalArgumentException("etf_factor calendar window must contain 1..366 days");
        return List.copyOf(DailyTradingSessions.read(calendars, from, to));
    }
}
