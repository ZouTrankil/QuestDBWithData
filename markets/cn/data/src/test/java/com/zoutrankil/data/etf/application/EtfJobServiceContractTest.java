package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.ExchangeCalendar;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.etf.domain.EtfTargetRange;
import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.etf.port.EtfAdjTarget;
import com.zoutrankil.data.etf.port.EtfAdjWriteSession;
import com.zoutrankil.data.etf.port.EtfWriteSession;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.TusharePageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Characterizes the distinct admission rules in the three pre-T14 job services. */
class EtfJobServiceContractTest {
    private static final String TARGET = "static-v2-" + "a".repeat(64);
    private static final String OTHER_TARGET = "static-v2-" + "b".repeat(64);
    private static final LocalDate DATE = LocalDate.of(2020, 1, 1);
    @TempDir Path temp;

    enum Family {
        DAILY(EtfDailySyncJobOwner.DEFINITION, "java_d014_etf_daily_contract", EtfDailySource.class),
        ADJ(EtfAdjSyncJobOwner.DEFINITION, "java_d015_etf_adj_contract", EtfAdjSource.class),
        FACTOR(EtfFactorSyncJobOwner.DEFINITION, "java_d017_etf_factor_contract", EtfFactorSource.class);

        final SyncJobDefinition definition;
        final String table;
        final Class<?> sourceClass;
        Family(SyncJobDefinition definition, String table, Class<?> sourceClass) {
            this.definition = definition; this.table = table; this.sourceClass = sourceClass;
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void everyPlanRequiresItsFrozenTarget(Family family) {
        var request = request(family, DATE, Mode.BACKFILL, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> plan(family, request, OTHER_TARGET, null, null, null, null));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void dailyPlanAllowsPhysicalMetadataThatAdjAndFactorReject(Family family) {
        var request = request(family, DATE, Mode.BACKFILL,
                Map.of("targetMinBefore", DATE, "targetMaxBefore", DATE));
        if (family == Family.DAILY) {
            assertDoesNotThrow(() -> plan(family, request, TARGET, null, null, null, null));
        } else {
            assertThrows(IllegalArgumentException.class,
                    () -> plan(family, request, TARGET, null, null, null, null));
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void onlyFactorPlanChecksCheckpointMetadataAndRequiresIncrementalAnchor(Family family) {
        var request = request(family, DATE, Mode.INCREMENTAL,
                Map.of("checkpointBefore", DATE, "checkpointAnchor", DATE));
        var noAnchor = request(family, DATE, Mode.INCREMENTAL, Map.of());
        if (family == Family.FACTOR) {
            assertThrows(IllegalArgumentException.class,
                    () -> plan(family, request, TARGET, DATE.minusDays(1), DATE, null, null));
            assertThrows(IllegalArgumentException.class,
                    () -> plan(family, noAnchor, TARGET, null, null, null, null));
        } else {
            assertDoesNotThrow(() -> plan(family, request, TARGET, DATE.minusDays(1), DATE, null, null));
            assertDoesNotThrow(() -> plan(family, noAnchor, TARGET, null, null, null, null));
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void invalidDefinitionKeepsItsOriginalExceptionTypeAndHasNoBackendEffects(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("invalid.sqlite3"));
        var other = family == Family.DAILY ? Family.ADJ : Family.DAILY;
        var invalid = plan(family, request(other, DATE, Mode.BACKFILL, Map.of()), TARGET,
                null, null, null, null);
        if (family == Family.FACTOR) {
            assertThrows(IllegalArgumentException.class, () -> h.run(invalid));
        } else {
            assertThrows(IllegalStateException.class, () -> h.run(invalid));
        }
        verifyNoInteractions(h.target, h.calendars, h.pages, h.jobs);
        assertFalse(Files.exists(h.ledger));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void changedTargetFailsBeforeRangeWriterOrLedgerCreation(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("target.sqlite3"));
        when(h.target.targetId()).thenReturn(OTHER_TARGET);
        assertThrows(IllegalStateException.class, () -> h.run(h.backfillPlan()));
        verify(h.target).targetId();
        verifyNoMoreInteractions(h.target);
        verifyNoInteractions(h.calendars, h.pages, h.jobs);
        assertFalse(Files.exists(h.ledger));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void onlyAdjAndFactorRecheckPhysicalRangeBeforeFreshRun(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("range.sqlite3"));
        h.closedCalendar();
        when(h.target.range()).thenReturn(new EtfTargetRange(DATE, DATE));
        if (family == Family.DAILY) {
            assertEquals(SyncRunState.VERIFIED_EMPTY, h.run(h.backfillPlan()).state());
            verify(h.target, never()).range();
            verify(h.target).newWriter(TARGET);
        } else {
            var failure = assertThrows(IllegalStateException.class, () -> h.run(h.backfillPlan()));
            assertTrue(failure.getMessage().contains("range changed"));
            verify(h.target).range();
            verify(h.target, never()).newWriter(anyString());
            verifyNoInteractions(h.calendars, h.pages, h.jobs);
            assertFalse(Files.exists(h.ledger));
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void onlyFactorRechecksTargetIdentityAfterReadingFreshBaseline(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("identity-after-range.sqlite3"));
        h.closedCalendar();
        when(h.target.targetId()).thenReturn(TARGET, OTHER_TARGET);
        if (family == Family.FACTOR) {
            var failure = assertThrows(IllegalStateException.class, () -> h.run(h.backfillPlan()));
            assertEquals("etf_factor physical target identity changed after planning", failure.getMessage());
            var order = inOrder(h.target);
            order.verify(h.target).targetId();
            order.verify(h.target).range();
            order.verify(h.target).targetId();
            verify(h.target, never()).newWriter(anyString());
            verifyNoInteractions(h.calendars, h.pages, h.jobs);
            assertFalse(Files.exists(h.ledger));
        } else {
            assertEquals(SyncRunState.VERIFIED_EMPTY, h.run(h.backfillPlan()).state());
            verify(h.target).targetId();
            verify(h.target, times(family == Family.DAILY ? 0 : 1)).range();
            verify(h.target).newWriter(TARGET);
            verifyNoInteractions(h.pages, h.jobs);
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void onlyAdjAndFactorRecheckVerifiedCheckpointBeforeFreshIncrementalRun(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("checkpoint.sqlite3"));
        h.closedCalendar();
        var request = request(family, DATE, Mode.INCREMENTAL,
                Map.of("checkpointBefore", DATE, "checkpointAnchor", DATE));
        var plan = plan(family, request, TARGET, DATE, DATE, null, null);
        if (family == Family.DAILY) {
            assertEquals(SyncRunState.VERIFIED_EMPTY, h.run(plan).state());
            verify(h.target, never()).range();
        } else {
            var failure = assertThrows(IllegalStateException.class, () -> h.run(plan));
            assertTrue(failure.getMessage().contains("verified checkpoint changed"));
            verify(h.target, never()).newWriter(anyString());
            verifyNoInteractions(h.calendars, h.pages, h.jobs);
            assertFalse(Files.exists(h.ledger));
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void resumeSkipsFreshBaselineAndKeepsSavedRequestAndParent(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("resume.sqlite3"));
        h.closedCalendar();
        var request = request(family, DATE, Mode.BACKFILL, Map.of());
        failedRun(h.ledger, "prior", request);
        when(h.target.range()).thenThrow(new AssertionError("Resume must not read today's physical range"));
        var result = h.resume(plan(family, request, TARGET, null, null, null, null), "prior");
        assertEquals(SyncRunState.VERIFIED_EMPTY, result.state());
        verify(h.target, never()).range();
        verify(h.target).newWriter(TARGET);
        assertEquals("prior", SyncRunLedger.openReadOnly(h.ledger).getRun(result.runId()).parentRunId());
        verifyNoInteractions(h.pages, h.jobs);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void resumeFingerprintMismatchIsEarlyForAdjAndFactorButRunnerOwnedForDaily(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("mismatch.sqlite3"));
        failedRun(h.ledger, "prior", request(family, DATE, Mode.BACKFILL, Map.of()));
        var changed = plan(family, request(family, DATE.plusDays(1), Mode.BACKFILL, Map.of()), TARGET,
                null, null, null, null);
        var failure = assertThrows(IllegalArgumentException.class, () -> h.resume(changed, "prior"));
        if (family == Family.DAILY) {
            assertEquals("Recovery definition, parameters, logical date or target changed", failure.getMessage());
            verify(h.target).targetId();
            verify(h.target).newWriter(TARGET);
            verifyNoMoreInteractions(h.target);
        } else {
            assertTrue(failure.getMessage().startsWith("Resume requires the exact saved etf_"));
            verifyNoInteractions(h.target);
        }
        verifyNoInteractions(h.calendars, h.pages, h.jobs, h.session);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void eachRunGetsFreshWriterAndSourceAfterLedgerWithItsOwnEvidencePath(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("fresh.sqlite3"));
        h.closedCalendar();
        var first = session(family);
        var second = session(family);
        var created = new AtomicInteger();
        var writers = List.of(first, second);
        when(h.target.newWriter(TARGET)).thenAnswer(invocation -> {
            assertTrue(Files.isRegularFile(h.ledger), "Ledger is constructed before the run writer");
            return writers.get(created.getAndIncrement());
        });
        var sourcePaths = new ArrayList<Path>();
        try (var sources = mockConstruction(family.sourceClass, (source, context) -> {
            assertEquals(sourcePaths.size() + 1, created.get(), "Writer precedes source construction");
            sourcePaths.add((Path) context.arguments().get(2));
        })) {
            var firstResult = h.run(h.backfillPlan());
            var secondResult = h.run(h.backfillPlan());
            assertEquals(SyncRunState.VERIFIED_EMPTY, firstResult.state());
            assertEquals(SyncRunState.VERIFIED_EMPTY, secondResult.state());
            assertNotEquals(firstResult.runId(), secondResult.runId());
            assertEquals(List.of(h.ledger.getParent().resolve("sync-evidence").resolve(firstResult.runId()).resolve("source"),
                    h.ledger.getParent().resolve("sync-evidence").resolve(secondResult.runId()).resolve("source")), sourcePaths);
            assertEquals(2, sources.constructed().size());
            assertNotSame(sources.constructed().getFirst(), sources.constructed().getLast());
        }
        verify(h.target, times(2)).newWriter(TARGET);
        verify(first).preflight();
        verify(second).preflight();
        verify(h.target, times(family == Family.DAILY ? 0 : 2)).range();
        verifyNoInteractions(h.pages, h.jobs);
    }

    @Test
    void factorCapsAnOversizedBootstrapWhileDailyAndAdjRejectBeforeTargetAccess() throws Exception {
        LocalDate through = DATE.plusYears(2);
        for (var family : List.of(Family.DAILY, Family.ADJ)) {
            var h = new Harness(family, temp.resolve(family + ".sqlite3"));
            var failure = assertThrows(IllegalArgumentException.class, () -> h.planIncremental(DATE, through));
            assertEquals("Explicit etf_" + family.name().toLowerCase() + " bootstrap exceeds the 366-day budget",
                    failure.getMessage());
            verifyNoInteractions(h.target, h.calendars, h.pages, h.jobs);
            assertFalse(Files.exists(h.ledger));
        }
        var h = new Harness(Family.FACTOR, temp.resolve("factor.sqlite3"));
        h.closedCalendar();
        when(h.jobs.prepare(anyString(), anyInt(), any(), anyMap(), any(), any(), any()))
                .thenAnswer(invocation -> Family.FACTOR.definition.freeze(invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5), invocation.getArgument(6)));
        var plan = (EtfFactorJobService.Plan) h.planIncremental(DATE, through);
        assertTrue(plan.bootstrap());
        assertTrue(plan.cappedByBudget());
        assertEquals(through, plan.requestedThrough());
        assertEquals(DATE.plusDays(365), plan.request().to());
        assertEquals(DATE, plan.checkpointAnchor());
        verify(h.target).newWriter(TARGET);
        verify(h.target).range();
        verify(h.session, times(2)).preflight();
        verify(h.session).readExistingDates();
        verifyNoInteractions(h.pages);
        assertFalse(Files.exists(h.ledger), "Planning does not create a run ledger");
    }

    private static FrozenRequest request(Family family, LocalDate date, Mode mode, Map<String, ?> extra) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("targetId", TARGET); parameters.put("trade_dates", "NONE"); parameters.putAll(extra);
        return family.definition.freeze(mode, parameters, date, date, date);
    }

    private static Object plan(Family family, FrozenRequest request, String target,
                               LocalDate checkpoint, LocalDate anchor, LocalDate min, LocalDate max) {
        return switch (family) {
            case DAILY -> new EtfDailyJobService.Plan(request, target, checkpoint, anchor, min, max, false);
            case ADJ -> new EtfAdjJobService.Plan(request, target, checkpoint, anchor, min, max, false);
            case FACTOR -> new EtfFactorJobService.Plan(request, target, checkpoint, anchor, min, max, request.to(), false, false);
        };
    }

    private static void failedRun(Path path, String id, FrozenRequest request) throws Exception {
        var ledger = new SyncRunLedger(path);
        ledger.createRun(id, null, TARGET, request);
        ledger.transition(id, 0, SyncRunState.FAILED, "{}");
    }

    @SuppressWarnings("unchecked")
    private static EtfWriteSession<Object, Object> session(Family family) {
        return (EtfWriteSession<Object, Object>) (EtfWriteSession<?, ?>)
                (family == Family.ADJ ? mock(EtfAdjWriteSession.class) : mock(EtfWriteSession.class));
    }

    private static final class Harness {
        final Family family;
        final Path ledger;
        final SyncJobRegistry jobs = mock(SyncJobRegistry.class);
        final TusharePageService pages = mock(TusharePageService.class);
        final ExchangeCalendarReadRepository calendars = mock(ExchangeCalendarReadRepository.class);
        final EtfTarget<Object, Object> target;
        final EtfWriteSession<Object, Object> session;
        final Object service;

        @SuppressWarnings("unchecked")
        Harness(Family family, Path ledger) {
            this.family = family; this.ledger = ledger;
            target = (EtfTarget<Object, Object>) (EtfTarget<?, ?>)
                    (family == Family.ADJ ? mock(EtfAdjTarget.class) : mock(EtfTarget.class));
            session = session(family);
            when(target.tableName()).thenReturn(family.table);
            when(target.targetId()).thenReturn(TARGET);
            when(target.range()).thenReturn(new EtfTargetRange(null, null));
            when(target.newWriter(TARGET)).thenReturn(session);
            when(session.readExistingDates()).thenReturn(List.of());
            service = switch (family) {
                case DAILY -> new EtfDailyJobService(jobs, pages, calendars, typedTarget(), ledger);
                case ADJ -> new EtfAdjJobService(jobs, pages, calendars, (EtfAdjTarget) (EtfTarget<?, ?>) target, ledger);
                case FACTOR -> new EtfFactorJobService(jobs, pages, calendars, typedTarget(), ledger);
            };
            clearInvocations(target);
        }

        @SuppressWarnings("unchecked")
        private <T, K> EtfTarget<T, K> typedTarget() { return (EtfTarget<T, K>) (EtfTarget<?, ?>) target; }

        Object backfillPlan() {
            return plan(family, request(family, DATE, Mode.BACKFILL, Map.of()), TARGET, null, null, null, null);
        }

        SyncJobRunner.Result run(Object plan) throws Exception {
            return switch (family) {
                case DAILY -> ((EtfDailyJobService) service).run((EtfDailyJobService.Plan) plan);
                case ADJ -> ((EtfAdjJobService) service).run((EtfAdjJobService.Plan) plan);
                case FACTOR -> ((EtfFactorJobService) service).run((EtfFactorJobService.Plan) plan);
            };
        }

        SyncJobRunner.Result resume(Object plan, String prior) throws Exception {
            return switch (family) {
                case DAILY -> ((EtfDailyJobService) service).resume((EtfDailyJobService.Plan) plan, prior);
                case ADJ -> ((EtfAdjJobService) service).resume((EtfAdjJobService.Plan) plan, prior);
                case FACTOR -> ((EtfFactorJobService) service).resume((EtfFactorJobService.Plan) plan, prior);
            };
        }

        Object planIncremental(LocalDate from, LocalDate through) throws Exception {
            return switch (family) {
                case DAILY -> ((EtfDailyJobService) service).plan(Mode.INCREMENTAL, from, through, through);
                case ADJ -> ((EtfAdjJobService) service).plan(Mode.INCREMENTAL, from, through, through);
                case FACTOR -> ((EtfFactorJobService) service).planDetailed(Mode.INCREMENTAL, from, through, through);
            };
        }

        void closedCalendar() {
            when(calendars.findPage(any())).thenAnswer(invocation -> {
                DatasetReadQuery query = invocation.getArgument(0);
                var rows = ((LocalDate) query.fromInclusive()).datesUntil((LocalDate) query.toExclusive())
                        .map(date -> new ExchangeCalendar("SSE", date, false, null)).toList();
                return new DatasetReadPage<>("exchange_calendar", 1, null, Instant.EPOCH, rows, null);
            });
        }
    }
}
