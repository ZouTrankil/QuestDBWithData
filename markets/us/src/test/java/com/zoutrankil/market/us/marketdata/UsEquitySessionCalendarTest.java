package com.zoutrankil.market.us.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.domain.DatasetDefinition.Capability;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

class UsEquitySessionCalendarTest {
    @Test
    void officialClosuresEarlyClosesAndDstUseTheApprovedYear() throws Exception {
        var calendar = UsEquitySessionCalendar.load();
        assertTrue(calendar.session(LocalDate.of(2026, 7, 3)).isEmpty());
        assertTrue(calendar.session(LocalDate.of(2026, 1, 3)).isEmpty());
        assertEquals(
                Instant.parse("2026-03-06T14:30:00Z"),
                calendar.session(LocalDate.of(2026, 3, 6)).orElseThrow().open());
        assertEquals(
                Instant.parse("2026-03-09T13:30:00Z"),
                calendar.session(LocalDate.of(2026, 3, 9)).orElseThrow().open());
        assertEquals(
                Instant.parse("2026-07-02T20:00:00Z"),
                calendar.session(LocalDate.of(2026, 7, 2)).orElseThrow().close());
        assertEquals(
                Instant.parse("2026-11-27T18:00:00Z"),
                calendar.session(LocalDate.of(2026, 11, 27)).orElseThrow().close());
        assertEquals(
                199,
                calendar.openDates().stream()
                        .filter(d -> !d.isAfter(LocalDate.of(2026, 10, 16)))
                        .count());
        assertThrows(
                IllegalArgumentException.class, () -> calendar.session(LocalDate.of(2027, 1, 4)));
        assertThrows(
                IllegalArgumentException.class,
                () -> calendar.requireRetainedSources(new byte[0], new byte[0]));
        assertTrue(UsEquitySessionCalendar.REGULAR.capabilities().contains(Capability.READ));
    }
}
