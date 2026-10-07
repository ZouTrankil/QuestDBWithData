package com.zoutrankil.data.service;

import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** Finite SSE-session calendar slices used by Python's margin_detail connector. */
public final class MarginDetailTradingDates {
    private final ExchangeCalendarReadRepository calendar;
    public MarginDetailTradingDates(ExchangeCalendarReadRepository calendar) { this.calendar=Objects.requireNonNull(calendar); }

    public List<LocalDate> read(LocalDate from, LocalDate to) {
        long days=ChronoUnit.DAYS.between(from,to)+1;
        if(from==null||to==null||days<1||days>MarginDetailSyncJobOwner.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("D029 calendar interval must be within the 14-day run bound");
        return List.copyOf(DailyTradingSessions.read(calendar,from,to));
    }

    /** Python margins use the latest SSE open date strictly before today's date. */
    public LocalDate sourceCeiling(LocalDate logicalDate) {
        Objects.requireNonNull(logicalDate,"D029 logical date required");
        LocalDate to=logicalDate.minusDays(1),from=to.minusDays(45);
        var dates=DailyTradingSessions.read(calendar,from,to);
        if(dates.isEmpty())throw new IllegalStateException("D029 cannot resolve the previous published SSE session");
        return dates.getLast();
    }
}
