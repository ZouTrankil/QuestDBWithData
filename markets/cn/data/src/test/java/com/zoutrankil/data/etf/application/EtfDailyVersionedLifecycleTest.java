package com.zoutrankil.data.etf.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;
import com.zoutrankil.data.etf.domain.EtfTargetRange;
import com.zoutrankil.data.etf.port.EtfDailyTarget;
import com.zoutrankil.data.etf.port.EtfDailyWriteSession;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

class EtfDailyVersionedLifecycleTest {
    private static final String TARGET = "static-v2-" + "a".repeat(64);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);
    @TempDir Path root;

    @Test
    void newV2PlanFreezesTheSelectedDefinitionAndUsesVersionedRangeAndWriter() throws Exception {
        var h = new Harness(root.resolve("plan.sqlite3"), CnTradeDateLayout.TRADE_DATE_V2);
        var plan = h.service.plan(Mode.BACKFILL, DAY, DAY, DAY);
        assertEquals(EtfDailySyncJobOwner.definition(2), plan.request().definition());
        verify(h.target).range(2);
        verify(h.target).newWriter(TARGET, 2);
        verify(h.target, never()).range();
        verify(h.target, never()).newWriter(TARGET);
        assertFalse(Files.exists(h.path));
        verifyNoInteractions(h.pages);
    }

    @Test
    void recoveryUsesTheOriginalVersionEvenWhenTheCurrentPlanVersionChanged() throws Exception {
        for (int original : List.of(1, 2)) {
            var configured =
                    original == 1 ? CnTradeDateLayout.TRADE_DATE_V2 : CnTradeDateLayout.LEGACY_V1;
            var h = new Harness(root.resolve("resume-" + original + ".sqlite3"), configured);
            var prior = request(original, Mode.BACKFILL, DAY, DAY, DAY);
            var ledger = new SyncRunLedger(h.path);
            ledger.createRun("prior", null, TARGET, prior);
            ledger.transition("prior", 0, SyncRunState.FAILED, "{}");
            String frozenBefore = ledger.getRun("prior").frozenJson();
            var result = h.service.resume("prior");
            assertEquals(SyncRunState.VERIFIED_EMPTY, result.state());
            var child = ledger.getRun(result.runId());
            assertEquals(original, child.jobVersion());
            assertEquals("prior", child.parentRunId());
            assertEquals(frozenBefore, child.frozenJson());
            assertEquals(frozenBefore, ledger.getRun("prior").frozenJson());
            verify(h.target).newWriter(TARGET, original);
            verify(h.target, never()).range();
            verify(h.target, never()).range(anyInt());
            verifyNoInteractions(h.jobs, h.pages);
        }
    }

    @Test
    void changedPhysicalIdentityAndNewPlansUsingTheWrongVersionFailBeforeWriterCreation()
            throws Exception {
        var h = new Harness(root.resolve("changed.sqlite3"), CnTradeDateLayout.TRADE_DATE_V2);
        var prior = request(1, Mode.BACKFILL, DAY, DAY, DAY);
        var ledger = new SyncRunLedger(h.path);
        ledger.createRun("prior", null, TARGET, prior);
        ledger.transition("prior", 0, SyncRunState.FAILED, "{}");
        assertThrows(
                IllegalStateException.class,
                () ->
                        h.service.run(
                                new EtfDailyJobService.Plan(
                                        prior, TARGET, null, null, null, null, false)));
        when(h.target.targetId()).thenReturn("static-v2-" + "b".repeat(64));
        assertThrows(IllegalStateException.class, () -> h.service.resume("prior"));
        verify(h.target, never()).newWriter(any(), anyInt());
        assertEquals(SyncRunState.FAILED, ledger.get("prior").state());
    }

    @Test
    void unresolvedV2RunStillRequiresOriginalLogReconciliationBeforeResume() throws Exception {
        var h = new Harness(root.resolve("unresolved.sqlite3"), CnTradeDateLayout.TRADE_DATE_V2);
        var ledger = new SyncRunLedger(h.path);
        ledger.createRun("prior", null, TARGET, request(2, Mode.BACKFILL, DAY, DAY, DAY));
        ledger.transition("prior", 0, SyncRunState.RUNNING, "{}");
        ledger.transition("prior", 1, SyncRunState.IN_DOUBT, "{}");
        String frozen = ledger.getRun("prior").frozenJson();
        assertThrows(IllegalStateException.class, () -> h.service.resume("prior"));
        assertEquals(SyncRunState.IN_DOUBT, ledger.get("prior").state());
        assertEquals(frozen, ledger.getRun("prior").frozenJson());
        verify(h.target, never()).newWriter(any(), anyInt());
        verifyNoInteractions(h.calendars, h.pages, h.jobs);
    }

    @Test
    void legacyWrapperCannotHideANewLayoutSessionFromTheFrozenVersionGuard() {
        var target = mock(EtfDailyTarget.class);
        var writer = mock(EtfDailyWriteSession.Versioned.class);
        when(target.newWriter(TARGET)).thenReturn(writer);
        when(writer.datasetVersion()).thenReturn(2);
        assertThrows(
                IllegalStateException.class,
                () -> EtfDailyTarget.writerForFrozenVersion(target, TARGET, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> EtfDailyTarget.writerForFrozenVersion(target, TARGET, 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> EtfDailyTarget.writerForFrozenVersion(target, TARGET, 3));
    }

    @Test
    void v2CheckpointsCannotBorrowAContinuousPrefixFromV1Receipts() throws Exception {
        Path path = root.resolve("coverage.sqlite3");
        var ledger = new SyncRunLedger(path);
        verifiedEmpty(ledger, "old", request(1, Mode.INCREMENTAL, DAY, DAY, DAY));
        verifiedEmpty(
                ledger,
                "new-tail",
                request(2, Mode.INCREMENTAL, DAY.plusDays(1), DAY.plusDays(2), DAY));
        var calendars = mock(EtfDailyTradingDates.class);
        when(calendars.read(any(), any())).thenReturn(List.of());
        assertEquals(
                DAY, EtfDailyCoverage.checkpoint(path, TARGET, calendars).orElseThrow().through());
        assertTrue(EtfDailyCoverage.checkpoint(path, TARGET, calendars, 2).isEmpty());
        verifiedEmpty(
                ledger, "new-bootstrap", request(2, Mode.INCREMENTAL, DAY, DAY.plusDays(2), DAY));
        assertEquals(
                DAY.plusDays(2),
                EtfDailyCoverage.checkpoint(path, TARGET, calendars, 2).orElseThrow().through());
        assertEquals(
                DAY,
                EtfDailyCoverage.checkpoint(path, TARGET, calendars, 1).orElseThrow().through());
    }

    private static void verifiedEmpty(
            SyncRunLedger ledger, String id, SyncJobDefinition.FrozenRequest request)
            throws Exception {
        ledger.createRun(id, null, TARGET, request);
        ledger.transition(id, 0, SyncRunState.RUNNING, "{}");
        ledger.transition(
                id,
                1,
                SyncRunState.VERIFIED_EMPTY,
                "{\"sourceComplete\":true,\"returnedRows\":0,\"submittedRows\":0,\"responseEvidence\":\"offline-closed-calendar\"}");
    }

    private static SyncJobDefinition.FrozenRequest request(
            int version, Mode mode, LocalDate from, LocalDate to, LocalDate anchor) {
        var params = new LinkedHashMap<String, Object>();
        params.put("targetId", TARGET);
        params.put("trade_dates", "NONE");
        if (mode == Mode.INCREMENTAL) params.put("checkpointAnchor", anchor);
        return EtfDailySyncJobOwner.definition(version).freeze(mode, params, from, to, to);
    }

    private static final class Harness {
        final Path path;
        final SyncJobRegistry jobs = mock(SyncJobRegistry.class);
        final TusharePageService pages = mock(TusharePageService.class);
        final ExchangeCalendarReadRepository calendars = mock(ExchangeCalendarReadRepository.class);
        final EtfDailyTarget.Versioned target = mock(EtfDailyTarget.Versioned.class);
        final EtfDailyJobService service;

        Harness(Path path, CnTradeDateLayout layout) throws Exception {
            this.path = path;
            when(target.tableName()).thenReturn("java_d014_etf_daily_versions");
            when(target.targetId()).thenReturn(TARGET);
            when(target.range(anyInt())).thenReturn(new EtfTargetRange(null, null));
            when(target.newWriter(eq(TARGET), anyInt()))
                    .thenAnswer(
                            invocation -> {
                                int version = invocation.getArgument(1);
                                var port = mock(EtfDailyWriteSession.Versioned.class);
                                when(port.datasetVersion()).thenReturn(version);
                                return port;
                            });
            when(calendars.findPage(any()))
                    .thenAnswer(
                            invocation -> {
                                DatasetReadQuery query = invocation.getArgument(0);
                                var rows =
                                        ((LocalDate) query.fromInclusive())
                                                .datesUntil((LocalDate) query.toExclusive())
                                                .map(
                                                        date ->
                                                                new ExchangeCalendar(
                                                                        "SSE", date, false, null))
                                                .toList();
                                return new DatasetReadPage<>(
                                        "exchange_calendar", 1, null, null, rows, null);
                            });
            when(jobs.prepare(any(), anyInt(), any(), anyMap(), any(), any(), any()))
                    .thenAnswer(
                            invocation -> {
                                int version = invocation.getArgument(1);
                                return EtfDailySyncJobOwner.definition(version)
                                        .freeze(
                                                invocation.getArgument(2),
                                                invocation.getArgument(3),
                                                invocation.getArgument(4),
                                                invocation.getArgument(5),
                                                invocation.getArgument(6));
                            });
            service = new EtfDailyJobService(jobs, pages, calendars, target, path, layout);
        }
    }
}
