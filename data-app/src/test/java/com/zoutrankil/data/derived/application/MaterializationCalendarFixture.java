package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.domain.*;
import java.time.*;
import java.util.*;

/** Complete typed natural-day rows for the explicitly named open sessions. */
final class MaterializationCalendarFixture {
    private MaterializationCalendarFixture() {}
    static DatasetReadPage<ExchangeCalendar> page(DatasetReadQuery query, List<LocalDate> openDates) {
        var rows = new ArrayList<ExchangeCalendar>();
        for (LocalDate day = (LocalDate) query.fromInclusive(); day.isBefore((LocalDate) query.toExclusive()); day = day.plusDays(1))
            rows.add(new ExchangeCalendar("SSE", day, openDates.contains(day), day.minusDays(1)));
        return new DatasetReadPage<>("exchange_calendar", 1, null, Instant.EPOCH, rows, null);
    }
}
