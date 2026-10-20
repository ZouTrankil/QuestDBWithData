package com.zoutrankil.data.stock.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.stock.domain.StockTargetRange;
import com.zoutrankil.data.stock.port.StockStDailyTables;
import com.zoutrankil.data.stock.port.StockStDailyTarget;
import com.zoutrankil.data.stock.port.StockStDailyWriteSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

/** 使用真实临时账本，覆盖当前布局变化后原请求的选择和检查点隔离。 */
class StockStDailyVersionedLifecycleTest {
    private static final String PHYSICAL = "static-v2-" + "b".repeat(64);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    @TempDir Path root;

    @Test
    void newPlanUsesSelectedVersionWithoutCreatingLedgerOrCallingSource() throws Exception {
        var h = new Harness(root.resolve("plan.sqlite"), 2);
        var plan = h.service.plan(Mode.BACKFILL, DAY, DAY, DAY);
        assertEquals(StockStDailySyncJobOwner.definition(2), plan.request().definition());
        assertEquals(logical(2), plan.targetId());
        assertEquals(
                List.of(StockStDailySyncJobOwner.definition(2)), h.service.syncJobDefinitions());
        verify(h.v2).range();
        verify(h.v1, never()).range();
        verifyNoInteractions(h.pages);
        assertFalse(Files.exists(h.path));
    }

    @Test
    void restoreAndResumeSelectOriginalScopeInBothDirectionsAndPreserveJson() throws Exception {
        for (int original : List.of(1, 2)) {
            var h = new Harness(root.resolve("resume" + original + ".sqlite"), 3 - original);
            var ledger = new SyncRunLedger(h.path);
            var request = request(original, Mode.BACKFILL, DAY, DAY);
            ledger.createRun("prior", null, logical(original), request);
            ledger.transition("prior", 0, SyncRunState.FAILED, "{}");
            String json = ledger.getRun("prior").frozenJson();
            var plan = h.service.restorePlan("prior");
            assertEquals(
                    SyncRequestIdentity.fingerprint(request, logical(original)),
                    SyncRequestIdentity.fingerprint(plan.request(), logical(original)));
            // 在预检主动失败，验证恢复选版和保留原请求，不制造来源/发布副作用。
            var writer = original == 1 ? h.writer1 : h.writer2;
            doThrow(new IllegalStateException("离线停止点")).when(writer).preflight();
            var result = h.service.resume(plan, "prior");
            assertEquals(SyncRunState.FAILED, result.state());
            assertEquals(original, ledger.getRun(result.runId()).jobVersion());
            assertEquals(json, ledger.getRun(result.runId()).frozenJson());
            assertEquals(json, ledger.getRun("prior").frozenJson());
            assertNull(
                    new DatasetIntervalLock(h.path)
                            .findOwned(
                                    result.runId(),
                                    new DatasetIntervalLock.Scope("stk_st_daily", DAY, DAY)));
            verify(original == 1 ? h.v2 : h.v1, never()).newWriter(anyString());
            verifyNoInteractions(h.jobs, h.pages);
        }
    }

    @Test
    void changedPhysicalGenerationAndUnresolvedRunFailBeforeWriterCreation() throws Exception {
        var h = new Harness(root.resolve("unsafe.sqlite"), 1);
        var ledger = new SyncRunLedger(h.path);
        ledger.createRun("prior", null, logical(2), request(2, Mode.BACKFILL, DAY, DAY));
        ledger.transition("prior", 0, SyncRunState.FAILED, "{}");
        when(h.v2.physicalTargetId()).thenReturn("static-v2-" + "c".repeat(64));
        assertThrows(IllegalStateException.class, () -> h.service.restorePlan("prior"));
        ledger.createRun("uncertain", null, logical(2), request(2, Mode.BACKFILL, DAY, DAY));
        ledger.transition("uncertain", 0, SyncRunState.RUNNING, "{}");
        ledger.transition("uncertain", 1, SyncRunState.IN_DOUBT, "{}");
        assertThrows(IllegalStateException.class, () -> h.service.restorePlan("uncertain"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        h.service.run(
                                new StockStDailyJobService.Plan(
                                        request(2, Mode.BACKFILL, DAY, DAY),
                                        logical(2),
                                        PHYSICAL,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false)));
        verify(h.v1, never()).newWriter(anyString());
        verify(h.v2, never()).newWriter(anyString());
        verifyNoInteractions(h.calendars, h.pages);
    }

    @Test
    void revalidationUsesOriginalScopeBeforeAnySourceCall() throws Exception {
        for (int original : List.of(1, 2)) {
            var h = new Harness(root.resolve("recheck" + original + ".sqlite"), 3 - original);
            var ledger = new SyncRunLedger(h.path);
            var request = request(original, Mode.BACKFILL, DAY, DAY);
            verified(ledger, "prior", request);
            String json = ledger.getRun("prior").frozenJson();
            var selected = original == 1 ? h.v1 : h.v2;
            when(selected.physicalTargetId()).thenThrow(new IllegalStateException("只读停止点"));
            assertThrows(IllegalStateException.class, () -> h.service.revalidate("prior"));
            verify(selected).physicalTargetId();
            verify(original == 1 ? h.v2 : h.v1, never()).physicalTargetId();
            assertEquals(json, ledger.getRun("prior").frozenJson());
            verifyNoInteractions(h.pages, h.calendars);
        }
    }

    @Test
    void continuousCheckpointCannotBorrowTheOtherLayoutsPrefixEvenUnderSameTargetId()
            throws Exception {
        var h = new Harness(root.resolve("coverage.sqlite"), 2);
        var ledger = new SyncRunLedger(h.path);
        // 故意使用相同 targetId，确认按定义筛选，而非只依赖逻辑身份的版本前缀。
        String target = logical(1);
        verified(ledger, "old", coverageRequest(1, target, DAY, DAY));
        verified(ledger, "tail", coverageRequest(2, target, DAY.plusDays(1), DAY.plusDays(2)));
        var dates = new StockStTradingDates(h.calendars);
        assertEquals(
                DAY,
                StockStDailyCoverage.checkpoint(h.path, target, dates).orElseThrow().through());
        assertTrue(StockStDailyCoverage.checkpoint(h.path, target, dates, 2).isEmpty());
        verified(ledger, "new-bootstrap", coverageRequest(2, target, DAY, DAY.plusDays(2)));
        assertEquals(
                DAY.plusDays(2),
                StockStDailyCoverage.checkpoint(h.path, target, dates, 2).orElseThrow().through());
        assertEquals(
                DAY,
                StockStDailyCoverage.checkpoint(h.path, target, dates, 1).orElseThrow().through());
        verifyNoInteractions(h.pages);
    }

    private static String logical(int version) {
        return "d012-logical-v" + version + "-" + "a".repeat(64);
    }

    private static SyncJobDefinition.FrozenRequest request(
            int version, Mode mode, LocalDate from, LocalDate to) {
        var params = new LinkedHashMap<String, Object>();
        params.put("targetId", logical(version));
        params.put("physicalTargetId", PHYSICAL);
        params.put("trade_dates", "NONE");
        if (mode == Mode.INCREMENTAL) params.put("checkpointAnchor", DAY);
        return StockStDailySyncJobOwner.definition(version).freeze(mode, params, from, to, to);
    }

    private static SyncJobDefinition.FrozenRequest coverageRequest(
            int version, String target, LocalDate from, LocalDate to) {
        var p = new LinkedHashMap<>(request(version, Mode.INCREMENTAL, from, to).parameters());
        p.put("targetId", target);
        return StockStDailySyncJobOwner.definition(version)
                .freeze(Mode.INCREMENTAL, p, from, to, to);
    }

    private static void verified(
            SyncRunLedger ledger, String id, SyncJobDefinition.FrozenRequest request)
            throws Exception {
        ledger.createRun(id, null, request.parameters().get("targetId").toString(), request);
        ledger.transition(id, 0, SyncRunState.RUNNING, "{}");
        ledger.transition(
                id,
                1,
                SyncRunState.VERIFIED_EMPTY,
                "{\"sourceComplete\":true,\"returnedRows\":0,\"submittedRows\":0,\"responseEvidence\":\"offline-closed-calendar\"}");
    }

    private static final class Harness {
        final Path path;
        final SyncJobRegistry jobs = mock(SyncJobRegistry.class);
        final TusharePageService pages = mock(TusharePageService.class);
        final ExchangeCalendarReadRepository calendars = mock(ExchangeCalendarReadRepository.class);
        final StockStDailyTarget.Versioned v1 = mock(StockStDailyTarget.Versioned.class);
        final StockStDailyTarget.Versioned v2 = mock(StockStDailyTarget.Versioned.class);
        final StockStDailyWriteSession.Versioned writer1 =
                mock(StockStDailyWriteSession.Versioned.class);
        final StockStDailyWriteSession.Versioned writer2 =
                mock(StockStDailyWriteSession.Versioned.class);
        final StockStDailyJobService service;

        Harness(Path path, int current) throws Exception {
            this.path = path;
            for (int v : List.of(1, 2)) {
                var target = v == 1 ? v1 : v2;
                var writer = v == 1 ? writer1 : writer2;
                var tables = mock(StockStDailyTables.Versioned.class);
                when(tables.datasetVersion()).thenReturn(v);
                when(target.datasetVersion()).thenReturn(v);
                when(target.tableName()).thenReturn("java_d012_stk_st_daily_lifecycle");
                when(target.targetId()).thenReturn(logical(v));
                when(target.physicalTargetId()).thenReturn(PHYSICAL);
                when(target.range()).thenReturn(new StockTargetRange(null, null));
                when(target.newWriter(PHYSICAL)).thenReturn(writer);
                when(writer.datasetVersion()).thenReturn(v);
                when(target.newPublicationTables()).thenReturn(tables);
                when(target.forVersion(1)).thenReturn(v1);
                when(target.forVersion(2)).thenReturn(v2);
            }
            when(calendars.findPage(any()))
                    .thenAnswer(
                            i -> {
                                DatasetReadQuery q = i.getArgument(0);
                                var rows =
                                        ((LocalDate) q.fromInclusive())
                                                .datesUntil((LocalDate) q.toExclusive())
                                                .map(
                                                        d ->
                                                                new ExchangeCalendar(
                                                                        "SSE", d, false, null))
                                                .toList();
                                return new DatasetReadPage<>(
                                        "exchange_calendar", 1, null, null, rows, null);
                            });
            when(jobs.prepare(any(), anyInt(), any(), anyMap(), any(), any(), any()))
                    .thenAnswer(
                            i ->
                                    StockStDailySyncJobOwner.definition(i.getArgument(1))
                                            .freeze(
                                                    i.getArgument(2),
                                                    i.getArgument(3),
                                                    i.getArgument(4),
                                                    i.getArgument(5),
                                                    i.getArgument(6)));
            service =
                    new StockStDailyJobService(
                            jobs, pages, calendars, current == 1 ? v1 : v2, path);
        }
    }
}
