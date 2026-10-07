package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;
import com.zoutrankil.data.calendar.application.ExchangeCalendarSyncAdapter;
import com.zoutrankil.data.calendar.port.ExchangeCalendarTarget;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.stock.domain.DailyBasicTargetRange;
import com.zoutrankil.data.stock.mapper.StockBasicMapper;
import com.zoutrankil.data.stock.port.*;
import com.zoutrankil.data.stock.storage.StockDetailInfoReadRepository;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.zoutrankil.data.domain.SyncJobDefinition.Mode.BACKFILL;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockCalendarOwnerPortContractTest {
    private static final LocalDate DAY = LocalDate.of(2020, 5, 4);
    private static final String TARGET = "questdb-" + "a".repeat(64);
    @TempDir Path temp;
    enum Family { DAILY, DAILY_BASIC, CALENDAR }

    @ParameterizedTest @EnumSource(Family.class)
    void invalidPlanningBoundsDoNotReadTargetsOrOpenLedgers(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("invalid.sqlite"));
        assertThrows(IllegalArgumentException.class, () -> h.plan(DAY.plusDays(1), DAY));
        verifyNoInteractions(h.target, h.pages, h.calendars, h.details);
        assertFalse(Files.exists(h.ledger));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void planningPreflightFailurePrecedesCalendarRangeAndLedgerAccess(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("preflight.sqlite"));
        var failure = new IllegalStateException("rejected physical schema"); h.sessionFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> h.plan(DAY, DAY)));
        assertEquals(1, h.sessions.size()); verify(h.sessions.getFirst()).preflight();
        verifyNoInteractions(h.calendars, h.pages, h.details);
        if (family == Family.DAILY_BASIC) verify(h.basicTarget, never()).range();
        assertFalse(Files.exists(h.ledger));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void changedTargetRejectsRunsBeforeWriterSourceOrLedger(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("changed.sqlite")); h.identity("questdb-" + "b".repeat(64));
        assertThrows(IllegalStateException.class, h::run);
        assertTrue(h.sessions.isEmpty()); verifyNoInteractions(h.calendars, h.pages, h.details);
        assertFalse(Files.exists(h.ledger));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void eachExecutionUsesAFreshSessionAndRecordsPreflightFailureWithoutFetching(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("fresh.sqlite")); h.closedCalendars();
        h.sessionFailure = new IllegalStateException("physical schema unavailable");
        var first = h.run(); var second = h.run();
        assertEquals(SyncRunState.FAILED, first.state()); assertEquals(SyncRunState.FAILED, second.state());
        assertNotEquals(first.runId(), second.runId()); assertEquals(2, h.sessions.size());
        assertNotSame(h.sessions.get(0), h.sessions.get(1));
        for (var session : h.sessions) { verify(session).preflight(); verifyNoMoreInteractions(session); }
        verifyNoInteractions(h.pages, h.details);
        var ledger = SyncRunLedger.openReadOnly(h.ledger);
        assertEquals(SyncRunState.FAILED, ledger.get(first.runId()).state());
        assertEquals(SyncRunState.FAILED, ledger.get(second.runId()).state());
        assertFalse(Files.exists(temp.resolve("sync-evidence")));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void parentCancellationStopsBeforeSessionPreflightAndSourceAccess(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("cancel.sqlite"));
        var ledger = new SyncRunLedger(h.ledger);
        ledger.createRun("parent", null, TARGET, h.request()); assertTrue(ledger.requestCancellation("parent"));
        var result = h.child("child", "parent");
        assertEquals(SyncRunState.CANCELLED, result.state()); assertEquals(SyncRunState.CANCELLED, ledger.get("child").state());
        assertEquals(1, h.sessions.size()); verifyNoInteractions(h.sessions.getFirst(), h.calendars, h.pages, h.details);
        assertTrue(ledger.cancellationRequested("parent"));
        assertFalse(Files.exists(temp.resolve("sync-evidence")));
    }

    @Test void dailyRejectsAnotherFrozenDefinitionBeforeTargetAccess() throws Exception {
        var h = new Harness(Family.DAILY, temp.resolve("definition.sqlite"));
        var request = DailyBasicJobService.definition().freeze(BACKFILL, Map.of("trade_dates", "NONE"), DAY, DAY, DAY);
        assertThrows(IllegalArgumentException.class, () -> h.daily.run(new DailyJobService.Plan(request, TARGET, null, 0)));
        verifyNoInteractions(h.target, h.pages, h.calendars, h.details); assertFalse(Files.exists(h.ledger));
    }

    @Test void stockBasicGroupIdentityFailurePrecedesRequestAndStorageCreation() throws Exception {
        var target = mock(StockBasicTarget.class); var jobs = mock(SyncJobRegistry.class); var pages = mock(TusharePageService.class);
        var mapper = new StockBasicMapper(); var ledger = temp.resolve("snapshot.sqlite");
        var owner = new StockBasicJobService(jobs, pages, mapper, target, ledger.toString());
        when(target.targetId()).thenReturn("other-generation");
        assertThrows(IllegalStateException.class, () -> owner.runAsGroupChild("child", "parent", null, TARGET,
                List.of("000001.SZ"), DAY));
        verify(target).targetId(); verifyNoMoreInteractions(target); verifyNoInteractions(jobs, pages);
        assertFalse(Files.exists(ledger));
    }

    @Test @SuppressWarnings("unchecked")
    void stockBasicOwnerCreatesFreshSessionsAndRecordsPreflightFailureWithoutSourceAccess() throws Exception {
        var target = mock(StockBasicTarget.class); var jobs = mock(SyncJobRegistry.class); var pages = mock(TusharePageService.class);
        Path path = temp.resolve("snapshot-fresh.sqlite");
        var request = StockBasicSyncAdapter.definition(true).freeze(null, Map.of("codes", List.of("000001.SZ")), null, null, DAY);
        when(jobs.prepare("data.stock_basic", 2, null, Map.of("codes", List.of("000001.SZ")), null, null, DAY)).thenReturn(request);
        when(target.targetId()).thenReturn(TARGET);
        var sessions = new ArrayList<VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey>>();
        when(target.newWriter()).thenAnswer(invocation -> {
            var session = (VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey>) mock(VerifiedWriteSession.class);
            doThrow(new IllegalStateException("schema unavailable")).when(session).preflight(); sessions.add(session); return session;
        });
        var owner = new StockBasicJobService(jobs, pages, new StockBasicMapper(), target, path.toString());
        var first = owner.run(List.of("000001.SZ"), DAY); var second = owner.run(List.of("000001.SZ"), DAY);
        assertEquals(SyncRunState.FAILED, first.state()); assertEquals(SyncRunState.FAILED, second.state());
        assertNotSame(sessions.get(0), sessions.get(1));
        for (var session : sessions) { verify(session).preflight(); verifyNoMoreInteractions(session); }
        verifyNoInteractions(pages);
        assertEquals(SyncRunState.FAILED, SyncRunLedger.openReadOnly(path).get(second.runId()).state());
    }

    @Test @SuppressWarnings("unchecked")
    void stockBasicParentCancellationStopsBeforePreflightAndFetch() throws Exception {
        var target = mock(StockBasicTarget.class); var jobs = mock(SyncJobRegistry.class); var pages = mock(TusharePageService.class);
        var session = (VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey>) mock(VerifiedWriteSession.class);
        Path path = temp.resolve("snapshot-cancel.sqlite");
        var request = StockBasicSyncAdapter.definition(true).freeze(null, Map.of("codes", List.of("000001.SZ")), null, null, DAY);
        when(jobs.prepare("data.stock_basic", 2, null, Map.of("codes", List.of("000001.SZ")), null, null, DAY)).thenReturn(request);
        when(target.targetId()).thenReturn(TARGET); when(target.newWriter()).thenReturn(session);
        var ledger = new SyncRunLedger(path); ledger.createRun("parent", null, TARGET, request); assertTrue(ledger.requestCancellation("parent"));
        var owner = new StockBasicJobService(jobs, pages, new StockBasicMapper(), target, path.toString());
        var result = owner.runAsGroupChild("child", "parent", null, TARGET, List.of("000001.SZ"), DAY);
        assertEquals(SyncRunState.CANCELLED, result.state()); assertEquals(SyncRunState.CANCELLED, ledger.get("child").state());
        verifyNoInteractions(session, pages); assertTrue(ledger.cancellationRequested("parent"));
    }

    private static final class Harness {
        final Family family; final Path ledger;
        final TusharePageService pages = mock(TusharePageService.class);
        final ExchangeCalendarReadRepository calendars = mock(ExchangeCalendarReadRepository.class);
        final StockDetailInfoReadRepository details = mock(StockDetailInfoReadRepository.class);
        final DailyTarget dailyTarget = mock(DailyTarget.class);
        final DailyBasicTarget basicTarget = mock(DailyBasicTarget.class);
        final ExchangeCalendarTarget calendarTarget = mock(ExchangeCalendarTarget.class);
        final Object target;
        final DailyJobService daily;
        final DailyBasicJobService basic;
        final ExchangeCalendarJobService calendar;
        final List<VerifiedWriteSession<?, ?>> sessions = new ArrayList<>();
        RuntimeException sessionFailure;

        Harness(Family family, Path ledger) throws Exception {
            this.family = family; this.ledger = ledger;
            when(dailyTarget.tableName()).thenReturn("java_d007_daily_contract");
            when(basicTarget.tableName()).thenReturn("java_daily_basic_contract");
            when(calendarTarget.tableName()).thenReturn("java_calendar_contract");
            target = switch (family) { case DAILY -> dailyTarget; case DAILY_BASIC -> basicTarget; case CALENDAR -> calendarTarget; };
            daily = family == Family.DAILY ? new DailyJobService(pages, calendars, details, dailyTarget, ledger) : null;
            basic = family == Family.DAILY_BASIC ? new DailyBasicJobService(pages, calendars, basicTarget, ledger) : null;
            calendar = family == Family.CALENDAR ? new ExchangeCalendarJobService(pages, calendarTarget, ledger.toString()) : null;
            identity(TARGET);
            doAnswer(invocation -> newSession()).when(dailyTarget).newWriter();
            doAnswer(invocation -> newSession()).when(basicTarget).newWriter();
            doAnswer(invocation -> newSession()).when(calendarTarget).newWriter();
            when(basicTarget.range()).thenReturn(new DailyBasicTargetRange(null, null));
            clearInvocations(target);
        }
        @SuppressWarnings("unchecked")
        private VerifiedWriteSession<?, ?> newSession() throws Exception {
            var session = family == Family.DAILY ? mock(DailyWriteSession.class) : mock(VerifiedWriteSession.class);
            if (sessionFailure != null) doThrow(sessionFailure).when(session).preflight();
            sessions.add(session); return session;
        }
        void identity(String value) throws Exception {
            switch (family) {
                case DAILY -> when(dailyTarget.targetId()).thenReturn(value);
                case DAILY_BASIC -> when(basicTarget.targetId()).thenReturn(value);
                case CALENDAR -> when(calendarTarget.targetId()).thenReturn(value);
            }
        }
        SyncJobDefinition.FrozenRequest request() {
            return switch (family) {
                case DAILY -> DailyJobService.definition().freeze(BACKFILL, Map.of(), DAY, DAY, DAY);
                case DAILY_BASIC -> DailyBasicJobService.definition().freeze(BACKFILL, Map.of("trade_dates", "NONE"), DAY, DAY, DAY);
                case CALENDAR -> ExchangeCalendarSyncAdapter.definition(true).freeze(BACKFILL, Map.of("exchanges", List.of("SSE")), DAY, DAY, DAY);
            };
        }
        Object plan(LocalDate from, LocalDate to) throws Exception {
            return switch (family) {
                case DAILY -> daily.plan(from, to, DAY, BACKFILL);
                case DAILY_BASIC -> basic.plan(from, to, DAY, BACKFILL);
                case CALENDAR -> calendar.plan(List.of("SSE"), from, to, DAY, BACKFILL);
            };
        }
        SyncJobRunner.Result run() throws Exception {
            return switch (family) {
                case DAILY -> daily.run(new DailyJobService.Plan(request(), TARGET, null, 0));
                case DAILY_BASIC -> basic.run(new DailyBasicJobService.Plan(request(), TARGET, null));
                case CALENDAR -> calendar.execute(new ExchangeCalendarJobService.Plan(request(), TARGET, Map.of(), 0), null);
            };
        }
        SyncJobRunner.Result child(String run, String parent) throws Exception {
            return switch (family) {
                case DAILY -> daily.runAsGroupChild(run, parent, TARGET, request());
                case DAILY_BASIC -> basic.runAsGroupChild(run, parent, null, TARGET, request());
                case CALENDAR -> calendar.runAsGroupChild(run, parent, null, TARGET, request());
            };
        }
        void closedCalendars() {
            when(calendars.findPage(any())).thenAnswer(invocation -> {
                var query = (DatasetReadQuery) invocation.getArgument(0);
                var rows = query.equalities().containsKey("exchange") ? List.of(new ExchangeCalendar("SSE", DAY, false, null))
                        : List.of(new ExchangeCalendar("SSE", DAY, false, null), new ExchangeCalendar("SZSE", DAY, false, null));
                return new DatasetReadPage<>("exchange_calendar", 1, null, Instant.EPOCH, rows, null);
            });
        }
    }
}
