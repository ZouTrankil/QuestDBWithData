package com.zoutrankil.data.service;

import com.zoutrankil.data.calendar.application.ExchangeCalendarSlices;

import com.zoutrankil.data.domain.ExchangeCalendar;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeCalendarSlicesTest {
    @Test void splitsEachExchangeAtYearBoundaryAndIncludesLeapDay() {
        var slices=ExchangeCalendarSlices.bounded(List.of("SSE","SZSE"),LocalDate.of(2023,12,31),LocalDate.of(2024,3,1));
        assertEquals(4,slices.size());
        assertEquals(1,slices.getFirst().expectedDays());
        assertEquals(61,slices.get(1).expectedDays());
        assertEquals("SZSE",slices.get(2).exchange());
        assertThrows(IllegalArgumentException.class,()->ExchangeCalendarSlices.bounded(
                List.of("SSE"),LocalDate.of(1990,1,1),LocalDate.of(2026,1,1)));
    }
    @Test void incrementalUsesSeparateVerifiedExchangeCoverageAndRereadsOverlap() {
        LocalDate start=LocalDate.of(2026,9,1), end=LocalDate.of(2026,9,30);
        var plan=ExchangeCalendarSlices.incremental(List.of("SSE","SZSE"),start,end,
                Map.of("SSE",LocalDate.of(2026,9,20),"SZSE",LocalDate.of(2026,9,10)),7);
        assertEquals(LocalDate.of(2026,9,14),plan.get(0).from());
        assertEquals(LocalDate.of(2026,9,4),plan.get(1).from());
        assertEquals(start,ExchangeCalendarSlices.incremental(List.of("SSE"),start,end,Map.of(),7).getFirst().from());
    }
    @Test void exactCoverageRejectsMissingDuplicateCrossExchangeAndOutOfRangeBeforeWrite() {
        LocalDate day=LocalDate.of(2026,9,26);
        var slice=new ExchangeCalendarSlices.Slice("SSE",day,day.plusDays(1));
        var a=new ExchangeCalendar("SSE",day,false,day.minusDays(1));
        var b=new ExchangeCalendar("SSE",day.plusDays(1),false,day.minusDays(1));
        assertEquals(List.of(a,b),ExchangeCalendarSlices.complete(slice,List.of(b,a)));
        assertThrows(IllegalArgumentException.class,()->ExchangeCalendarSlices.complete(slice,List.of()));
        assertThrows(IllegalArgumentException.class,()->ExchangeCalendarSlices.complete(slice,List.of(a)));
        assertThrows(IllegalArgumentException.class,()->ExchangeCalendarSlices.complete(slice,List.of(a,a)));
        assertThrows(IllegalArgumentException.class,()->ExchangeCalendarSlices.complete(slice,List.of(a,
                new ExchangeCalendar("SZSE",b.calendarDate(),false,b.previousTradeDate()))));
        assertThrows(IllegalArgumentException.class,()->ExchangeCalendarSlices.complete(slice,List.of(a,
                new ExchangeCalendar("SSE",day.plusDays(2),true,day.minusDays(1)))));
    }
}
