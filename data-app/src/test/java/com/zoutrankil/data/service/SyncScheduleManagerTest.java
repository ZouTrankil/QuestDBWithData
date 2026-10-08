package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.application.StockBasicSyncAdapter;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.SyncScheduleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SyncScheduleManagerTest {
    @TempDir Path temp;
    private static SyncScheduleDefinition def(boolean enabled, SyncScheduleDefinition.DayRule dayRule,
                                              SyncScheduleDefinition.Misfire misfire, Duration lateness) {
        return new SyncScheduleDefinition("stock-morning", SyncScheduleDefinition.Target.JOB,
                "data.stock_basic",2,enabled,ZoneId.of("Asia/Shanghai"),
                SyncScheduleDefinition.Kind.DAILY,LocalTime.of(9,30),Set.of(),dayRule,misfire,lateness,
                Map.of("codes","000001.SZ"));
    }
    private static SyncScheduleManager manager(SyncScheduleStore store, Instant now,
                                                 java.util.function.Predicate<LocalDate> session) {
        var dataset=new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION));
        var job=StockBasicSyncAdapter.definition(true);
        var jobs=new SyncJobRegistry(List.of(job),dataset,
                Map.of(job.datasetId(),job.supportedModes()),new SyncJobRegistry.Policies(
                        Set.of(job.ratePolicyRef()),Set.of(job.slicePolicyRef()),Set.of(job.verificationPolicyRef())));
        return new SyncScheduleManager(store,jobs,new SyncGroupRegistry(List.of(),jobs),session,Clock.fixed(now,ZoneOffset.UTC));
    }
    @Test void dueAndRepeatedTickUseOneRunnerResult() throws Exception {
        var store=new SyncScheduleStore(temp.resolve("schedule.sqlite3"));
        Instant due=Instant.parse("2026-09-29T01:30:00Z");
        var manager=manager(store,due,d -> true);
        manager.put(def(true,SyncScheduleDefinition.DayRule.WEEKDAY,SyncScheduleDefinition.Misfire.SKIP,Duration.ofMinutes(5)));
        var calls=new AtomicInteger();
        var first=manager.tick((d,s) -> { calls.incrementAndGet(); return new SyncScheduleManager.RunResult("run-one",SyncRunState.VERIFIED); });
        assertEquals(1,calls.get()); assertEquals(SyncScheduleStore.State.VERIFIED,first.getFirst().state());
        manager.tick((d,s) -> { calls.incrementAndGet(); throw new AssertionError("duplicate dispatch"); });
        assertEquals(1,calls.get());
        assertEquals(LocalDate.of(2026,9,30),manager.status("stock-morning").next().localDate());
        assertEquals("run-one",new SyncScheduleStore(temp.resolve("schedule.sqlite3"))
                .history("stock-morning",10).getFirst().runId());
        var existing=store.get("stock-morning");
        assertThrows(IllegalArgumentException.class, () -> store.put(new SyncScheduleDefinition(
                existing.scheduleId(),existing.target(),existing.targetId(),existing.targetVersion(),
                existing.enabled(),existing.zone(),existing.kind(),LocalTime.of(10,30),existing.days(),
                existing.dayRule(),existing.misfire(),existing.maxLateness(),existing.parameters())));
        store.put(existing.withEnabled(false));
        assertFalse(store.get("stock-morning").enabled());
    }
    @Test void disabledMisfireReentryAndUnknownOutcomeRemainExplicit() throws Exception {
        var store=new SyncScheduleStore(temp.resolve("schedule.sqlite3"));
        Instant due=Instant.parse("2026-09-29T01:30:00Z");
        var disabled=manager(store,due,d -> true);
        disabled.put(def(false,SyncScheduleDefinition.DayRule.CALENDAR,SyncScheduleDefinition.Misfire.SKIP,Duration.ofMinutes(5)));
        assertTrue(disabled.tick((d,s) -> { throw new AssertionError(); }).isEmpty());
        disabled.setEnabled("stock-morning",true);
        assertEquals(SyncScheduleStore.State.MISSED,manager(store,due.plusSeconds(60),d -> true)
                .tick((d,s) -> { throw new AssertionError(); }).getFirst().state());
        assertTrue(store.claim("stock-morning",due.plus(Duration.ofDays(1)),SyncScheduleStore.State.CLAIMED,"test-crash"));
        assertFalse(store.claim("stock-morning",due.plus(Duration.ofDays(2)),SyncScheduleStore.State.CLAIMED,"reentry"));
        assertEquals(SyncScheduleStore.State.SKIPPED_REENTRY,store.history("stock-morning",1).getFirst().state());
        assertEquals(SyncScheduleStore.State.CLAIMED,store.history("stock-morning",2).get(1).state());
    }
    @Test void boundedMisfirePreservesScheduledLogicalDateAndNeverReplaysException() throws Exception {
        var store = new SyncScheduleStore(temp.resolve("misfire.sqlite"));
        Instant due = Instant.parse("2026-09-29T01:30:00Z");
        var late = manager(store, due.plusSeconds(60), d -> true);
        late.put(def(true, SyncScheduleDefinition.DayRule.CALENDAR,
                SyncScheduleDefinition.Misfire.RUN_ONCE, Duration.ofMinutes(5)));
        var calls = new AtomicInteger();
        var result = late.tick((d, slot) -> {
            calls.incrementAndGet();
            assertEquals(LocalDate.of(2026, 9, 29), slot.localDate());
            assertEquals(due, slot.dueAt());
            throw new java.io.IOException("unknown delivery");
        });
        assertEquals(SyncScheduleStore.State.IN_DOUBT, result.getFirst().state());
        var reopened = new SyncScheduleStore(temp.resolve("misfire.sqlite"));
        manager(reopened, due.plusSeconds(120), d -> true).tick((d,s) -> {
            throw new AssertionError("same slot must not replay");
        });
        var next = manager(reopened, due.plus(Duration.ofDays(1)), d -> true)
                .tick((d,s) -> { throw new AssertionError("unresolved run blocks next day"); });
        assertEquals(SyncScheduleStore.State.SKIPPED_REENTRY, next.getFirst().state());
        assertEquals(1, calls.get());
    }
    @Test void expiredMisfireDoesNotCallRunnerAndPartialOutcomeIsNotSuccess() throws Exception {
        var store = new SyncScheduleStore(temp.resolve("expired.sqlite"));
        Instant due = Instant.parse("2026-09-29T01:30:00Z");
        var expired = manager(store, due.plusSeconds(301), d -> true);
        expired.put(def(true, SyncScheduleDefinition.DayRule.CALENDAR,
                SyncScheduleDefinition.Misfire.RUN_ONCE, Duration.ofMinutes(5)));
        assertEquals(SyncScheduleStore.State.MISSED, expired.tick((d,s) -> {
            throw new AssertionError("outside bounded catchup");
        }).getFirst().state());
        var next = manager(store, due.plus(Duration.ofDays(1)), d -> true);
        var result = next.tick((d,s) -> new SyncScheduleManager.RunResult("partial-run", SyncRunState.PARTIAL));
        assertEquals(SyncScheduleStore.State.PARTIAL, result.getFirst().state());
        assertEquals("partial-run", result.getFirst().runId());
    }
    @Test void minuteIntervalDispatchesLatestDueSlotInsteadOfTruncatedLookback() throws Exception {
        var store = new SyncScheduleStore(temp.resolve("interval.sqlite"));
        Instant now = Instant.parse("2026-09-29T01:30:00Z");
        var manager = manager(store, now, d -> true);
        manager.put(new SyncScheduleDefinition("minute.schedule", SyncScheduleDefinition.Target.JOB,
                "data.stock_basic", 2, true, ZoneOffset.UTC, SyncScheduleDefinition.Kind.INTERVAL,
                LocalTime.MIDNIGHT, Set.of(), SyncScheduleDefinition.DayRule.CALENDAR,
                SyncScheduleDefinition.Misfire.RUN_ONCE, Duration.ofDays(7), Map.of("codes", "000001.SZ"), 1));
        var calls = new AtomicInteger();
        var result = manager.tick((d, slot) -> {
            assertEquals(now, slot.dueAt());
            calls.incrementAndGet();
            return new SyncScheduleManager.RunResult("latest-minute", SyncRunState.VERIFIED_EMPTY);
        });
        assertEquals(1, calls.get());
        assertEquals(now, result.getFirst().dueAt());
    }
    @Test void zoneDayAndDstAreCalculatedWithoutDispatch() {
        var sample=def(true,SyncScheduleDefinition.DayRule.EXCHANGE_SESSION,
                SyncScheduleDefinition.Misfire.RUN_ONCE,Duration.ofHours(2));
        assertEquals(LocalDate.of(2026,9,30),sample.nextAfter(Instant.parse("2026-09-29T01:30:00Z"),
                d -> d.equals(LocalDate.of(2026,9,30))).orElseThrow().localDate());
        var dst=new SyncScheduleDefinition("dst-test",SyncScheduleDefinition.Target.JOB,"data.stock_basic",2,
                true,ZoneId.of("America/New_York"),SyncScheduleDefinition.Kind.DAILY,LocalTime.of(2,30),
                Set.of(),SyncScheduleDefinition.DayRule.CALENDAR,SyncScheduleDefinition.Misfire.RUN_ONCE,
                Duration.ofHours(1),Map.of());
        assertEquals(LocalDate.of(2026,3,9),dst.nextAfter(Instant.parse("2026-03-08T05:00:00Z"),d -> true)
                .orElseThrow().localDate());
        var monthEnd=new SyncScheduleDefinition("month-end",SyncScheduleDefinition.Target.JOB,"data.stock_basic",2,
                true,ZoneId.of("Asia/Shanghai"),SyncScheduleDefinition.Kind.MONTH_END,LocalTime.of(16,0),
                Set.of(),SyncScheduleDefinition.DayRule.WEEKDAY,SyncScheduleDefinition.Misfire.RUN_ONCE,
                Duration.ofHours(1),Map.of());
        assertEquals(LocalDate.of(2026,10,30),monthEnd.nextAfter(Instant.parse("2026-10-01T00:00:00Z"),d -> true)
                .orElseThrow().localDate());
        var interval=new SyncScheduleDefinition("interval",SyncScheduleDefinition.Target.JOB,"data.stock_basic",2,
                true,ZoneId.of("Asia/Shanghai"),SyncScheduleDefinition.Kind.INTERVAL,LocalTime.of(9,0),
                Set.of(),SyncScheduleDefinition.DayRule.WEEKDAY,SyncScheduleDefinition.Misfire.RUN_ONCE,
                Duration.ofMinutes(2),Map.of(),5);
        assertEquals(Instant.parse("2026-09-29T01:10:00Z"),interval.nextAfter(
                Instant.parse("2026-09-29T01:05:00Z"),d -> true).orElseThrow().dueAt());
    }
}
