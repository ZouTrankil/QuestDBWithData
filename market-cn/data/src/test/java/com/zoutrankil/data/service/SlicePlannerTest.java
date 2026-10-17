package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncSlice;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SlicePlannerTest {
    private LocalDate day(String date) { return LocalDate.parse(date); }
    private List<SyncSlice> collect(Iterable<SyncSlice> slices) {
        var result = new ArrayList<SyncSlice>(); slices.forEach(result::add); return result;
    }
    @Test void inclusiveLeapDayWindowsAndCartesianDimensionsHaveNoGaps() {
        var slices = collect(SlicePlanner.windows(day("2024-02-28"), day("2024-03-02"), 2,
                List.of("a", "b"), List.of("P", "D"), 8));
        assertEquals(8, slices.size());
        assertEquals(day("2024-02-29"), slices.getFirst().end());
        assertEquals(day("2024-03-01"), slices.get(4).start());
        assertEquals(day("2024-03-02"), slices.getLast().end());
        assertEquals(8, new HashSet<>(slices).size());
    }
    @Test void monthAndQuarterLabelsAreSeparateFromClippedSourceDates() {
        var months = collect(SlicePlanner.months(day("2024-02-10"), day("2024-03-05"), List.of(), List.of(), 2));
        assertEquals(day("2024-02-29"), months.getFirst().periodEnd());
        assertEquals(day("2024-03-31"), months.getLast().periodEnd());
        assertEquals(day("2024-03-05"), months.getLast().end());
        var quarters = collect(SlicePlanner.quarters(day("2023-12-15"), day("2024-04-01"), List.of(), List.of(), 3));
        assertEquals(List.of(day("2023-12-31"), day("2024-03-31"), day("2024-06-30")),
                quarters.stream().map(SyncSlice::periodEnd).toList());
    }
    @Test void tradingCalendarRequiresCoverageAndPlansAreBoundedBeforeExecution() {
        var calendar = new SlicePlanner.TradingCalendar(day("2024-02-28"), day("2024-03-03"),
                List.of(day("2024-02-28"), day("2024-02-29"), day("2024-03-01")), "test frozen calendar");
        assertEquals(3, collect(SlicePlanner.tradingDays(calendar.coveredStart(), calendar.coveredEnd(), calendar,
                List.of(), List.of(), 3)).size());
        assertThrows(IllegalArgumentException.class, () -> SlicePlanner.tradingDays(day("2024-02-27"), calendar.coveredEnd(),
                calendar, List.of(), List.of(), 5));
        assertThrows(IllegalArgumentException.class, () -> SlicePlanner.windows(
                day("1900-01-01"), day("9999-12-31"), 1, List.of(), List.of(), 2));
        assertThrows(IllegalArgumentException.class, () -> SlicePlanner.windows(day("2024-01-01"), day("2024-01-01"),
                1, List.of("a", "a"), List.of(), 2));
    }
}
