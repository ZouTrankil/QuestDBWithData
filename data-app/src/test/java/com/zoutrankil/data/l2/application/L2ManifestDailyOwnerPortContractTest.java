package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.l2.mapper.*;
import com.zoutrankil.data.l2.port.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual runner/SQLite checkpoint authority, with physical target and source process replaced by ports. */
@SuppressWarnings({"unchecked", "rawtypes"})
class L2ManifestDailyOwnerPortContractTest {
    static final LocalDate DAY = L2ManifestDailySourceContractTest.DAY;
    static final String ID = "questdb-" + "a".repeat(64), OTHER = "questdb-" + "b".repeat(64);
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void invalidWindowSymbolsAndModeHaveNoTargetSourceOrLedgerSideEffect(boolean daily) throws Exception {
        var h = new Harness(daily, directory.resolve("invalid.sqlite"));
        assertThrows(IllegalArgumentException.class, () -> h.plan(DAY.minusDays(31), DAY, Mode.BACKFILL, List.of()));
        assertThrows(IllegalArgumentException.class, () -> h.plan(DAY, DAY, Mode.BACKFILL, List.of("000001.SZ", "000001.SZ")));
        assertThrows(IllegalArgumentException.class, () -> h.plan(DAY, DAY, Mode.BACKFILL, List.of("bad")));
        verifyNoInteractions(h.target, h.source, h.calendars); assertFalse(Files.exists(h.path));
        assertEquals(300_000, h.definition().budget().maxRows());
        assertEquals(25_000, h.definition().budget().maxSlices()); assertEquals(25_000, h.definition().budget().maxPages());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void planningPreservesInspectionBudgetAndFrozenSourceIdentity(boolean daily) throws Exception {
        var h = new Harness(daily, directory.resolve("plan.sqlite"));
        Object plan = h.plan(DAY, DAY, Mode.BACKFILL, List.of("000002.SZ", "000001.SZ"));
        var request = h.request(plan);
        assertEquals(List.of("000001.SZ", "000002.SZ"), request.parameters().get("symbols"));
        assertEquals(L2ManifestDailySourceContractTest.ROOT, request.parameters().get("source_root_id"));
        assertEquals(DAY, request.from()); assertEquals(DAY, request.to()); assertEquals(DAY.plusDays(1), request.logicalDate());
        assertEquals(1, h.writers.size()); verify(h.writers.getFirst()).preflight();
        h.verifyInspect(List.of("000001.SZ", "000002.SZ"));
        assertTrue(Files.isRegularFile(h.path)); assertTrue(SyncRunLedger.openReadOnly(h.path).history(h.definition().jobId(), null, 100).isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void planPreflightFailurePrecedesLedgerCalendarAndSource(boolean daily) throws Exception {
        var h = new Harness(daily, directory.resolve("preflight.sqlite")); h.failPreflight = true;
        assertThrows(IllegalStateException.class, () -> h.plan(DAY, DAY, Mode.BACKFILL, List.of()));
        assertFalse(Files.exists(h.path)); verifyNoInteractions(h.source, h.calendars);
        assertEquals(1, h.writers.size()); verify(h.writers.getFirst(), never()).send(anyList());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void uncheckpointedPhysicalRowsAreRejectedEvenForReconcile(boolean daily) throws Exception {
        var h = new Harness(daily, directory.resolve("physical.sqlite")); h.existing = List.of(DAY);
        assertThrows(IllegalStateException.class, () -> h.plan(DAY, DAY, Mode.RECONCILE, List.of()));
        h.verifyInspect(List.of()); verify(h.writers.getFirst(), never()).send(anyList());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void changedTargetRejectsBeforeNewWriterAndLedger(boolean daily) throws Exception {
        var h = new Harness(daily, directory.resolve("drift.sqlite")); h.identity = OTHER;
        assertThrows(IllegalStateException.class, () -> h.run(h.frozenPlan(), null));
        assertTrue(h.writers.isEmpty()); assertFalse(Files.exists(h.path)); verifyNoInteractions(h.source, h.calendars);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void eachAttemptGetsNewSessionAndResumeRevalidatesBeforeReusingVerifiedSlice(boolean daily) throws Exception {
        var h = new Harness(daily, directory.resolve("resume.sqlite")); Object plan = h.frozenPlan();
        var first = h.run(plan, null); assertEquals(SyncRunState.VERIFIED, first.state());
        var resumed = h.run(plan, first.runId()); assertEquals(SyncRunState.VERIFIED, resumed.state());
        assertEquals(1, resumed.reusedRows()); assertNotEquals(first.runId(), resumed.runId());
        assertEquals(2, h.writers.size()); assertNotSame(h.writers.get(0), h.writers.get(1));
        verify(h.writers.get(0)).send(anyList()); verify(h.writers.get(1), never()).send(anyList());
        verify(h.writers.get(1)).readback(anyList());
        var ledger = SyncRunLedger.openReadOnly(h.path);
        assertNull(ledger.getRun(first.runId()).parentRunId()); assertEquals(first.runId(), ledger.getRun(resumed.runId()).parentRunId());
        assertEquals(SyncRequestIdentity.snapshotJson(h.request(plan)), ledger.getRun(resumed.runId()).frozenJson());
        assertTrue(ledger.entries(first.runId(), null, 100).stream().anyMatch(e -> e.kind() == SyncRunLedger.Kind.SLICE && e.state() == SyncRunState.VERIFIED));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cancelledRunStopsBeforeSourceAndResumeUsesANewSession(boolean daily) throws Exception {
        var h = new Harness(daily, directory.resolve("cancel.sqlite")); h.cancelDuringPreflight = true;
        Object plan = h.frozenPlan(); var cancelled = h.run(plan, null);
        assertEquals(SyncRunState.CANCELLED, cancelled.state()); assertEquals(0, h.fetches);
        verify(h.writers.getFirst(), never()).send(anyList());
        h.cancelDuringPreflight = false;
        var resumed = h.run(plan, cancelled.runId()); assertEquals(SyncRunState.VERIFIED, resumed.state());
        assertEquals(2, h.writers.size()); assertNotSame(h.writers.get(0), h.writers.get(1));
        assertEquals(cancelled.runId(), SyncRunLedger.openReadOnly(h.path).getRun(resumed.runId()).parentRunId());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void changedInspectionFailsRunBeforeStreamOrSend(boolean daily) throws Exception {
        var h = new Harness(daily, directory.resolve("source-drift.sqlite"));
        if (daily) when(((L2DailyFeaturesParquetSource) h.source).inspect(any(), any(), anyList(), anyInt(), anyInt())).thenThrow(new IllegalStateException("changed"));
        else when(((L2DatasetManifestParquetSource) h.source).inspect(any(), any(), anyList(), anyInt(), anyInt())).thenThrow(new IllegalStateException("changed"));
        var result = h.run(h.frozenPlan(), null); assertEquals(SyncRunState.FAILED, result.state());
        assertEquals(0, h.fetches); verify(h.writers.getFirst(), never()).send(anyList());
    }

    static final class Harness {
        final boolean daily; final Path path; final ExchangeCalendarReadPort calendars = mock(ExchangeCalendarReadPort.class);
        final Object source, target, owner, inspection; final Object row;
        final List<VerifiedWriteSession> writers = new ArrayList<>();
        String identity = ID; List<LocalDate> existing = List.of(); boolean failPreflight, cancelDuringPreflight; int fetches;
        Harness(boolean daily, Path path) throws Exception {
            this.daily = daily; this.path = path; inspection = L2ManifestDailySourceContractTest.inspection(daily);
            when(calendars.findPage(any())).thenReturn(new DatasetReadPage<>("exchange_calendar", 1, null, Instant.EPOCH,
                    List.of(new ExchangeCalendar("SSE", DAY, true, DAY.minusDays(1))), null));
            var raw = L2ManifestDailySourceContractTest.stream(daily).get(1).path("rows").get(0);
            if (daily) {
                row = new L2DailyFeaturesMapper().fromParquet(raw, DAY);
                var s = mock(L2DailyFeaturesParquetSource.class); source = s;
                when(s.sourceRootIdentity()).thenReturn(L2ManifestDailySourceContractTest.ROOT);
                when(s.inspect(any(), any(), anyList(), anyInt(), anyInt())).thenReturn((L2DailyFeaturesParquetSource.Inspection) inspection);
                when(s.stream(any(), anyList(), anyInt(), anyInt(), any(), any(), any())).thenAnswer(this::fetch);
                var t = mock(L2DailyFeaturesTarget.class); target = t; when(t.targetId()).thenAnswer(i -> identity);
                when(t.newWriter()).thenAnswer(i -> writer()); owner = new L2DailyFeaturesJobService(calendars, t, s, path);
            } else {
                row = new L2DatasetManifestMapper().fromParquet(raw);
                var s = mock(L2DatasetManifestParquetSource.class); source = s;
                when(s.sourceRootIdentity()).thenReturn(L2ManifestDailySourceContractTest.ROOT);
                when(s.inspect(any(), any(), anyList(), anyInt(), anyInt())).thenReturn((L2DatasetManifestParquetSource.Inspection) inspection);
                when(s.stream(any(), anyList(), anyInt(), anyInt(), any(), any(), any())).thenAnswer(this::fetch);
                var t = mock(L2DatasetManifestTarget.class); target = t; when(t.targetId()).thenAnswer(i -> identity);
                when(t.newWriter()).thenAnswer(i -> writer()); owner = new L2DatasetManifestJobService(calendars, t, s, path);
            }
        }
        private Object fetch(org.mockito.invocation.InvocationOnMock call) throws Exception {
            fetches++; assertEquals(300_000, (int)call.getArgument(2)); assertEquals(25_000, (int)call.getArgument(3));
            assertFalse(((BooleanSupplier)call.getArgument(5)).getAsBoolean());
            ((SyncJobRunner.PageConsumer)call.getArgument(4)).accept(new SyncJobRunner.Page<>(List.of(row), "f".repeat(64), "{\"receipt\":true}", "page-1"));
            return new SyncJobRunner.SourceCompletion(1, 1, true, "{\"complete\":true}");
        }
        private VerifiedWriteSession writer() throws Exception {
            VerifiedWriteSession writer;
            if (daily) { var w = mock(L2DailyFeaturesWriteSession.class); when(w.codec()).thenReturn(L2DailyFeaturesWriteSession.CODEC); when(w.readExistingDates()).thenAnswer(i -> existing); writer = w; }
            else { var w = mock(L2DatasetManifestWriteSession.class); when(w.codec()).thenReturn(L2DatasetManifestWriteSession.CODEC); when(w.readExistingDates()).thenAnswer(i -> existing); writer = w; }
            when(writer.walSettled()).thenReturn(true); when(writer.uncertainSenderStopped()).thenReturn(true);
            when(writer.readback(anyList())).thenReturn(List.of(row));
            doAnswer(i -> {
                if (failPreflight) throw new IllegalStateException("preflight failed");
                if (cancelDuringPreflight) {
                    var ledger = new SyncRunLedger(path); var runs = ledger.history(definition().jobId(), null, 10);
                    assertEquals(1, runs.size()); assertTrue(ledger.requestCancellation(runs.getFirst().id()));
                }
                return null;
            }).when(writer).preflight(); writers.add(writer); return writer;
        }
        SyncJobDefinition definition() { return daily ? L2DailyFeaturesJobService.definition() : L2DatasetManifestJobService.definition(); }
        Object plan(LocalDate from, LocalDate to, Mode mode, List<String> symbols) throws Exception {
            return daily ? ((L2DailyFeaturesJobService)owner).plan(from, to, DAY.plusDays(1), mode, symbols)
                    : ((L2DatasetManifestJobService)owner).plan(from, to, DAY.plusDays(1), mode, symbols);
        }
        Object frozenPlan() {
            var request = definition().freeze(Mode.BACKFILL, Map.of("source_root_id", L2ManifestDailySourceContractTest.ROOT), DAY, DAY, DAY.plusDays(1));
            return daily ? new L2DailyFeaturesJobService.Plan(request, ID, DAY, null, 0, (L2DailyFeaturesParquetSource.Inspection)inspection)
                    : new L2DatasetManifestJobService.Plan(request, ID, DAY, null, 0, (L2DatasetManifestParquetSource.Inspection)inspection);
        }
        SyncJobDefinition.FrozenRequest request(Object plan) { return daily ? ((L2DailyFeaturesJobService.Plan)plan).request() : ((L2DatasetManifestJobService.Plan)plan).request(); }
        SyncJobRunner.Result run(Object plan, String prior) throws Exception {
            if (daily) return prior == null ? ((L2DailyFeaturesJobService)owner).run((L2DailyFeaturesJobService.Plan)plan) : ((L2DailyFeaturesJobService)owner).resume((L2DailyFeaturesJobService.Plan)plan, prior);
            return prior == null ? ((L2DatasetManifestJobService)owner).run((L2DatasetManifestJobService.Plan)plan) : ((L2DatasetManifestJobService)owner).resume((L2DatasetManifestJobService.Plan)plan, prior);
        }
        void verifyInspect(List<String> symbols) throws Exception {
            if (daily) verify((L2DailyFeaturesParquetSource)source).inspect(DAY, DAY, symbols, 300_000, 25_000);
            else verify((L2DatasetManifestParquetSource)source).inspect(DAY, DAY, symbols, 300_000, 25_000);
        }
    }
}
