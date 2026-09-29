package com.zoutrankil.questdbwithdata.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlicePlannerBudgetTest {
    @Test void rejectsOversizedPlanBeforeAnySliceCanBeConsumed() {
        LocalDate start = LocalDate.of(2026, 9, 28);
        assertThrows(IllegalArgumentException.class,
                () -> SlicePlanner.windows(start, start.plusDays(2), 1,
                        List.of("000001.SZ"), List.of(), 2));
        assertThrows(IllegalArgumentException.class,
                () -> SlicePlanner.months(start, start.plusMonths(1),
                        List.of("000001.SZ", "000002.SZ"), List.of(), 3));
    }

    @Test void boundaryNearLargestLocalDateDoesNotOverflow() {
        LocalDate end = LocalDate.MAX;
        var slices = SlicePlanner.windows(end.minusDays(1), end, Integer.MAX_VALUE,
                List.of(), List.of(), 1).iterator();
        var slice = slices.next();
        assertEquals(end, slice.end());
        assertFalse(slices.hasNext());
    }
}
