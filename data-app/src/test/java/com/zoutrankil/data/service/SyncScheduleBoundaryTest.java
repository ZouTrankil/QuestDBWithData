package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncScheduleDefinition;
import com.zoutrankil.data.repository.SyncScheduleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.domain.SyncScheduleDefinition.*;
import static com.zoutrankil.data.repository.SyncScheduleStore.State.*;

class SyncScheduleBoundaryTest {
    @TempDir Path temp;
    private SyncScheduleDefinition definition(ZoneId zone, LocalTime time, DayRule rule) {
        return new SyncScheduleDefinition("schedule.sample", Target.JOB, "sample.job", 1,
                false, zone, Kind.DAILY, time, Set.of(), rule, Misfire.SKIP, Duration.ofMinutes(5), java.util.Map.of());
    }
    @Test void nonexistentDstTimeIsSkippedAndOverlapRunsOnlyOnce() {
        var zone = ZoneId.of("America/New_York");
        var gap = definition(zone, LocalTime.of(2,30), DayRule.CALENDAR);
        assertEquals(Instant.parse("2026-03-09T06:30:00Z"),
                gap.nextAfter(Instant.parse("2026-03-08T00:00:00Z"), d -> true).orElseThrow().dueAt());
        var overlap = definition(zone, LocalTime.of(1,30), DayRule.CALENDAR);
        var first = overlap.nextAfter(Instant.parse("2026-11-01T00:00:00Z"), d -> true).orElseThrow();
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), first.dueAt());
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"),
                overlap.nextAfter(first.dueAt(), d -> true).orElseThrow().dueAt());
    }
    @Test void exchangeCalendarKeepsLocalLogicalDateAcrossUtcMidnight() {
        var schedule = definition(ZoneId.of("Asia/Shanghai"), LocalTime.of(0,30), DayRule.EXCHANGE_SESSION);
        var day = LocalDate.of(2026,9,30);
        var slot = schedule.nextAfter(Instant.parse("2026-09-28T17:00:00Z"), day::equals).orElseThrow();
        assertEquals(day, slot.localDate());
        assertEquals(Instant.parse("2026-09-29T16:30:00Z"), slot.dueAt());
        assertTrue(schedule.nextAfter(slot.dueAt(), d -> false).isEmpty());
    }
    @Test void competingStoreInstancesClaimExactlyOneSlot() throws Exception {
        var path = temp.resolve("competing.sqlite");
        var first = new SyncScheduleStore(path);
        var second = new SyncScheduleStore(path);
        var definition = definition(ZoneOffset.UTC, LocalTime.NOON, DayRule.CALENDAR);
        first.put(definition);
        var gate = new java.util.concurrent.CountDownLatch(1);
        var due = Instant.parse("2026-09-29T12:00:00Z");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); return first.claim(definition.scheduleId(), due, CLAIMED, "a"); });
            var b = pool.submit(() -> { gate.await(); return second.claim(definition.scheduleId(), due, CLAIMED, "b"); });
            gate.countDown();
            boolean claimedA = a.get(10, java.util.concurrent.TimeUnit.SECONDS);
            boolean claimedB = b.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(claimedA, claimedB);
        }
        assertEquals(1, first.history(definition.scheduleId(), 10).size());
        assertEquals(CLAIMED, second.history(definition.scheduleId(), 1).getFirst().state());
    }
    @Test void unresolvedSubmissionBlocksFutureSlotsAfterStoreReopens() throws Exception {
        var path = temp.resolve("schedule.sqlite");
        var store = new SyncScheduleStore(path);
        var definition = definition(ZoneOffset.UTC, LocalTime.NOON, DayRule.CALENDAR);
        store.put(definition);
        var due = Instant.parse("2026-09-29T12:00:00Z");
        assertTrue(store.claim(definition.scheduleId(), due, CLAIMED, "due"));
        store.finish(definition.scheduleId(), due, IN_DOUBT, "run-1", "unknown delivery");
        var reopened = new SyncScheduleStore(path);
        assertEquals(definition, reopened.get(definition.scheduleId()));
        assertFalse(reopened.claim(definition.scheduleId(), due.plusSeconds(86400), CLAIMED, "next"));
        assertTrue(reopened.history(definition.scheduleId(), 10).stream()
                .anyMatch(h -> h.state() == SKIPPED_REENTRY));
        assertFalse(reopened.claim(definition.scheduleId(), due, CLAIMED, "duplicate"));
        assertThrows(IllegalStateException.class, () -> reopened.finish(definition.scheduleId(), due,
                VERIFIED, "run-2", "cannot rewrite terminal outcome"));
    }
}
