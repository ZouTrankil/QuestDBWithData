package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Complete typed natural-day rows for the explicitly named open sessions. */
final class MaterializationCalendarFixture {
    private MaterializationCalendarFixture() {}

    static DatasetReadPage<ExchangeCalendar> page(
            DatasetReadQuery query, List<LocalDate> openDates) {
        var rows = new ArrayList<ExchangeCalendar>();
        for (LocalDate day = (LocalDate) query.fromInclusive();
                day.isBefore((LocalDate) query.toExclusive());
                day = day.plusDays(1))
            rows.add(new ExchangeCalendar("SSE", day, openDates.contains(day), day.minusDays(1)));
        return new DatasetReadPage<>("exchange_calendar", 1, null, Instant.EPOCH, rows, null);
    }
}
