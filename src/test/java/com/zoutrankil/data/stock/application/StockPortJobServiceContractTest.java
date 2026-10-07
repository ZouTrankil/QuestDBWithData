package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.*;
import com.zoutrankil.data.stock.domain.StockDetailState;
import com.zoutrankil.data.stock.domain.StockTargetRange;
import com.zoutrankil.data.stock.domain.policy.StockDetailInfoMerge;
import com.zoutrankil.data.stock.mapper.StockDetailInfoMapper;
import com.zoutrankil.data.stock.port.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs the real local ledger and orchestration against isolated target/source doubles. */
class StockPortJobServiceContractTest {
    private static final String ID = "static-v2-" + "a".repeat(64);
    private static final String OTHER = "static-v2-" + "b".repeat(64);
    private static final String LOGICAL = "d012-logical-v1-" + "c".repeat(64);
    private static final LocalDate DAY = LocalDate.of(2020, 1, 4);
    @TempDir Path root;
    enum Family { FACTOR, LIMIT, ST }

    @ParameterizedTest @EnumSource(Family.class)
    @SuppressWarnings({"unchecked", "rawtypes"})
    void eachRunAndResumeCreatesItsOwnWriterSourceAndEvidenceRootBeforePreflightFails(Family family) throws Exception {
        var h = new Harness(family, root.resolve("run.sqlite"));
        var sourceRoots = new ArrayList<Path>();
        Class sourceType = switch (family) { case FACTOR -> StockFactorSource.class; case LIMIT -> StockLimitSource.class; case ST -> StockStDailySource.class; };
        try (var sources = mockConstruction(sourceType, (mock, context) -> sourceRoots.add((Path) context.arguments().getLast()))) {
            var first = h.run(Mode.BACKFILL); var second = h.run(Mode.BACKFILL);
            var resumed = h.resume(first.runId());
            assertEquals(3, h.writers.size()); assertNotSame(h.writers.get(0), h.writers.get(1));
            assertNotSame(h.writers.get(0), h.writers.get(2)); assertEquals(3, sources.constructed().size());
            var results = List.of(first, second, resumed);
            var ledger = SyncRunLedger.openReadOnly(h.ledger);
            for (int index = 0; index < results.size(); index++) {
                var result = results.get(index);
                assertEquals(SyncRunState.FAILED, result.state()); assertEquals("IllegalStateException", result.errorCode());
                assertEquals(root.resolve("sync-evidence").resolve(result.runId()).resolve("source"), sourceRoots.get(index));
                assertEquals(SyncRunState.FAILED, ledger.get(result.runId()).state());
                verify(h.writers.get(index)).preflight();
                verify(h.writers.get(index), never()).send(anyList());
                assertNull(new DatasetIntervalLock(h.ledger).findOwned(result.runId(),
                        new DatasetIntervalLock.Scope(h.dataset(), DAY, DAY)));
            }
            assertNull(ledger.getRun(first.runId()).parentRunId());
            assertEquals(first.runId(), ledger.getRun(resumed.runId()).parentRunId());
        }
        verifyNoInteractions(h.pages, h.jobs);
        if (family == Family.ST) {
            verify(h.st, never()).newStaging(); verify(h.st, never()).stageWriter(anyString(), anyString());
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void changedTargetStopsBeforeCreatingTheLedgerOrWriter(Family family) throws Exception {
        var h = new Harness(family, root.resolve("absent/run.sqlite"));
        switch (family) {
            case FACTOR -> when(h.factor.targetId()).thenReturn(OTHER);
            case LIMIT -> when(h.limit.targetId()).thenReturn(OTHER);
            case ST -> when(h.st.physicalTargetId()).thenReturn(OTHER);
        }
        assertThrows(IllegalStateException.class, () -> h.run(Mode.BACKFILL));
        assertTrue(h.writers.isEmpty()); assertFalse(Files.exists(h.ledger));
        verifyNoInteractions(h.pages, h.calendars, h.jobs);
    }

    @ParameterizedTest @EnumSource(value = Family.class, names = {"LIMIT", "ST"})
    void formalIncrementalAdmissionFailsBeforeAnyIdentityOrWriterIo(Family family) throws Exception {
        var h = new Harness(family, root.resolve("absent/run.sqlite"));
        assertThrows(IllegalArgumentException.class, () -> h.run(Mode.INCREMENTAL));
        if (family == Family.LIMIT) verify(h.limit, never()).targetId();
        else { verify(h.st, never()).targetId(); verify(h.st, never()).physicalTargetId(); }
        assertTrue(h.writers.isEmpty()); assertFalse(Files.exists(h.ledger));
        verifyNoInteractions(h.pages, h.calendars, h.jobs);
    }

    @ParameterizedTest @EnumSource(value = Family.class, names = {"LIMIT", "ST"})
    void changedFrozenCalendarIsRejectedBeforeWriterPreflight(Family family) throws Exception {
        var h = new Harness(family, root.resolve("calendar.sqlite"));
        when(h.calendars.findPage(any())).thenReturn(calendar(true));
        var result = h.run(Mode.BACKFILL);
        assertEquals(SyncRunState.FAILED, result.state()); assertEquals(1, h.writers.size());
        verify(h.writers.getFirst(), never()).preflight(); verify(h.writers.getFirst(), never()).send(anyList());
        verifyNoInteractions(h.pages);
    }

    @Test void factorPlanPassesExactCodeToTheTargetAndRechecksIdentityAfterRange() throws Exception {
        var h = new Harness(Family.FACTOR, root.resolve("not-created.sqlite"));
        var calls = new ArrayList<String>();
        when(h.factor.targetId()).thenAnswer(i -> { calls.add("identity"); return ID; });
        when(h.factor.range("000001.SZ")).thenAnswer(i -> { calls.add("range"); return new StockTargetRange(DAY, DAY); });
        when(h.jobs.prepare(anyString(), anyInt(), any(), anyMap(), any(), any(), any())).thenAnswer(i ->
                StockFactorSyncJobOwner.DEFINITION.freeze(i.getArgument(2), i.getArgument(3), i.getArgument(4), i.getArgument(5), i.getArgument(6)));
        var owner = (StockFactorJobService) h.service;
        assertThrows(IllegalArgumentException.class, () -> owner.planDetailed(Mode.BACKFILL, DAY, DAY, DAY, "invalid"));
        assertTrue(calls.isEmpty());
        var plan = owner.planDetailed(Mode.BACKFILL, DAY, DAY, DAY, "000001.SZ");
        assertEquals(List.of("identity", "range", "identity"), calls);
        assertEquals("000001.SZ", plan.request().parameters().get("tsCode"));
        assertEquals(DAY, plan.request().parameters().get("targetMinBefore"));
        assertEquals(DAY, plan.request().parameters().get("targetMaxBefore"));
        assertFalse(Files.exists(h.ledger)); assertTrue(h.writers.isEmpty());
    }

    @Test void detailCreatesAFreshTableAndPreflightsBeforeCreatingAnyRunOrSource() throws Exception {
        var pages = mock(TusharePageService.class); var target = detailTarget(); var ledger = root.resolve("absent/detail.sqlite");
        var first = mock(StockDetailTarget.Table.class); var second = mock(StockDetailTarget.Table.class);
        when(target.open("stock_detail_info")).thenReturn(first, second);
        var failure = new IllegalStateException("non-WAL schema changed");
        when(first.preflight()).thenThrow(failure); when(second.preflight()).thenThrow(failure);
        var owner = new StockDetailInfoJobService(pages, target, ledger);
        var request = owner.plan(List.of("000001.SZ"), false, DAY);
        for (int index = 0; index < 2; index++) assertSame(failure, assertThrows(IllegalStateException.class, () -> owner.run(request)));
        verify(first).preflight(); verify(second).preflight(); verify(target, times(2)).open("stock_detail_info");
        verify(target, never()).newStaging(); verifyNoInteractions(pages); assertFalse(Files.exists(ledger));
    }

    @Test void detailChangedSnapshotIdentityFailsBeforeSourceAndReleasesTheWholeTableLease() throws Exception {
        var pages = mock(TusharePageService.class); var target = detailTarget(); var table = mock(StockDetailTarget.Table.class);
        when(target.open("stock_detail_info")).thenReturn(table);
        var initial = new StockDetailState.Identity(7, "before"); when(table.preflight()).thenReturn(initial);
        when(target.identify("stock_detail_info", initial)).thenReturn(ID);
        when(table.snapshot()).thenReturn(new StockDetailState.Snapshot(new StockDetailState.Identity(8, "after"), List.of(), "f", 0));
        Path path = root.resolve("detail.sqlite"); var owner = new StockDetailInfoJobService(pages, target, path);
        var result = owner.run(owner.plan(List.of("000001.SZ"), false, DAY));
        assertEquals(SyncRunState.FAILED, result.state()); verifyNoInteractions(pages); verify(target, never()).newStaging();
        assertNull(new DatasetIntervalLock(path).findOwned(result.runId(), DatasetIntervalLock.Scope.allDates("stock_detail_info")));
    }

    @Test void detailStagingFailureRetainsPreparedEvidenceAndUncertainLeaseWithoutRenaming() throws Exception {
        var pages = mock(TusharePageService.class); var target = detailTarget(); var table = mock(StockDetailTarget.Table.class);
        var stage = mock(StockDetailTarget.StageWriter.class); when(target.open("stock_detail_info")).thenReturn(table);
        var identity = new StockDetailState.Identity(7, "generation");
        var before = new StockDetailState.Snapshot(identity, List.of(), "before", 0);
        when(table.preflight()).thenReturn(identity); when(table.snapshot()).thenReturn(before);
        when(target.identify("stock_detail_info", identity)).thenReturn(ID); when(target.newStaging()).thenReturn(stage);
        var row = new StockDetailInfo("000001.SZ", Instant.EPOCH, "000001", "sample", null, null, "L", null,
                null, null, null, null, null, null, null, null, null, null);
        var merge = StockDetailInfoMerge.merge(List.of(), List.of(row));
        var prepared = new StockDetailState.Prepared(before, List.of(new StockDetailInfoMapper().toStorage(row)), merge);
        when(target.prepare(before, List.of(row))).thenReturn(prepared);
        doAnswer(i -> {
            Path evidence = i.getArgument(1); assertTrue(Files.isRegularFile(evidence.resolve("prepared-publication.json")));
            throw new IllegalStateException("staging interrupted");
        }).when(stage).write(eq(prepared), any(Path.class), any());
        Path ledger = root.resolve("detail.sqlite"); var owner = new StockDetailInfoJobService(pages, target, ledger);
        try (var source = mockConstruction(StockDetailInfoSource.class, (mock, context) ->
                when(mock.fetch(eq("000001.SZ"), any(Instant.class), any())).thenReturn(
                        new SyncJobRunner.Page<>(List.of(row), "source", "source-receipt", null)))) {
            var result = owner.run(owner.plan(List.of("000001.SZ"), false, DAY));
            assertEquals(SyncRunState.IN_DOUBT, result.state()); assertEquals(1, source.constructed().size());
            var lease = new DatasetIntervalLock(ledger).findOwned(result.runId(), DatasetIntervalLock.Scope.allDates("stock_detail_info"));
            assertNotNull(lease); assertTrue(lease.inDoubt());
            assertTrue(Files.isRegularFile(root.resolve("sync-evidence").resolve(result.runId()).resolve("prepared-publication.json")));
        }
        verify(target, never()).rename(anyString(), anyString()); verify(target, never()).publicationTables(); verifyNoInteractions(pages);
    }

    private static StockDetailTarget detailTarget() {
        var target = mock(StockDetailTarget.class); when(target.tableName()).thenReturn("stock_detail_info"); return target;
    }
    private static DatasetReadPage<ExchangeCalendar> calendar(boolean open) {
        return new DatasetReadPage<>("exchange_calendar", 1, null, Instant.EPOCH, List.of(new ExchangeCalendar("SSE", DAY, open, null)), null);
    }
    private static final class Harness {
        final Family family; final Path ledger; final SyncJobRegistry jobs = mock(SyncJobRegistry.class);
        final TusharePageService pages = mock(TusharePageService.class);
        final ExchangeCalendarReadRepository calendars = mock(ExchangeCalendarReadRepository.class);
        final StockFactorTarget factor = mock(StockFactorTarget.class); final StockLimitTarget limit = mock(StockLimitTarget.class);
        final StockStDailyTarget st = mock(StockStDailyTarget.class); final Object service;
        final List<VerifiedWriteSession<?, ?>> writers = new ArrayList<>();
        @SuppressWarnings("unchecked") Harness(Family family, Path ledger) {
            this.family = family; this.ledger = ledger;
            when(factor.tableName()).thenReturn("stk_factor"); when(factor.targetId()).thenReturn(ID);
            when(limit.tableName()).thenReturn("stk_limit"); when(limit.targetId()).thenReturn(ID);
            when(st.tableName()).thenReturn("stk_st_daily"); when(st.targetId()).thenReturn(LOGICAL); when(st.physicalTargetId()).thenReturn(ID);
            when(st.newPublicationTables()).thenReturn(mock(StockStDailyTables.class));
            when(factor.newWriter(ID)).thenAnswer(i -> newWriter(false));
            when(limit.newWriter(ID)).thenAnswer(i -> newWriter(true));
            when(st.newWriter(ID)).thenAnswer(i -> newWriter(true));
            when(calendars.findPage(any())).thenReturn(calendar(false));
            service = switch (family) {
                case FACTOR -> new StockFactorJobService(jobs, pages, factor, ledger);
                case LIMIT -> new StockLimitJobService(jobs, pages, calendars, limit, ledger);
                case ST -> new StockStDailyJobService(jobs, pages, calendars, st, ledger);
            };
        }
        private VerifiedWriteSession<?, ?> newWriter(boolean dated) {
            assertTrue(Files.isRegularFile(ledger), "The per-run ledger must exist before writer construction");
            VerifiedWriteSession<?, ?> writer = dated ? mock(StockDateWriteSession.class) : mock(VerifiedWriteSession.class);
            doThrow(new IllegalStateException("writer preflight rejected")).when(writer).preflight();
            writers.add(writer); return writer;
        }
        String dataset() { return switch (family) { case FACTOR -> "stk_factor"; case LIMIT -> "stk_limit"; case ST -> "stk_st_daily"; }; }
        FrozenRequest request(Mode mode) {
            var parameters = new LinkedHashMap<String,Object>(); parameters.put("targetId", family == Family.ST ? LOGICAL : ID);
            if (family != Family.FACTOR) parameters.put("trade_dates", "NONE");
            if (family == Family.ST) parameters.put("physicalTargetId", ID);
            if (mode == Mode.INCREMENTAL) parameters.put("checkpointAnchor", DAY);
            return switch (family) {
                case FACTOR -> StockFactorSyncJobOwner.DEFINITION.freeze(mode, parameters, DAY, DAY, DAY);
                case LIMIT -> StockLimitSyncJobOwner.DEFINITION.freeze(mode, parameters, DAY, DAY, DAY);
                case ST -> StockStDailySyncJobOwner.DEFINITION.freeze(mode, parameters, DAY, DAY, DAY);
            };
        }
        SyncJobRunner.Result run(Mode mode) throws Exception {
            var request = request(mode);
            return switch (family) {
                case FACTOR -> ((StockFactorJobService) service).run(request);
                case LIMIT -> ((StockLimitJobService) service).run(new StockLimitJobService.Plan(request, ID, null, null, null, null, false));
                case ST -> ((StockStDailyJobService) service).run(new StockStDailyJobService.Plan(request, LOGICAL, ID, null, null, null, null, false));
            };
        }
        SyncJobRunner.Result resume(String prior) throws Exception {
            var request = request(Mode.BACKFILL);
            return switch (family) {
                case FACTOR -> ((StockFactorJobService) service).resume(request, prior);
                case LIMIT -> ((StockLimitJobService) service).resume(new StockLimitJobService.Plan(request, ID, null, null, null, null, false), prior);
                case ST -> ((StockStDailyJobService) service).resume(new StockStDailyJobService.Plan(request, LOGICAL, ID, null, null, null, null, false), prior);
            };
        }
    }
}
