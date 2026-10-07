package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.etf.domain.EtfTargetRange;
import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.etf.port.EtfWriteTarget;
import com.zoutrankil.data.etf.port.EtfWriteSession;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
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

/** Keeps the snapshot, announcement-date and trade-date ETF contracts distinct. */
class EtfOtherJobServiceContractTest {
    private static final String TARGET = "static-v2-" + "a".repeat(64);
    private static final String OTHER = "static-v2-" + "b".repeat(64);
    private static final LocalDate DATE = LocalDate.of(2020, 1, 4); // Saturday is still a portfolio announcement date.
    private static final Instant OBSERVED = Instant.parse("2020-01-04T01:02:03.123456Z");
    @TempDir Path temp;

    enum Family {
        BASIC(EtfBasicSyncJobOwner.DEFINITION, "java_d013_etf_basic_contract", EtfBasicSource.class),
        PORTFOLIO(EtfPortfolioSyncJobOwner.DEFINITION, "java_d018_etf_portfolio_contract", EtfPortfolioSource.class),
        SHARE(EtfShareSyncJobOwner.DEFINITION, "java_d016_etf_share_contract", EtfShareSource.class);
        final SyncJobDefinition definition;
        final String table;
        final Class<?> source;
        Family(SyncJobDefinition definition, String table, Class<?> source) {
            this.definition = definition; this.table = table; this.source = source;
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void planKeepsItsFrozenTarget(Family family) {
        assertThrows(IllegalArgumentException.class, () -> plan(family, request(family, OBSERVED), OTHER));
    }

    @Test
    void basicPlanRequiresExactCanonicalObservationAndSnapshotShape() {
        var request = request(Family.BASIC, OBSERVED);
        assertThrows(IllegalArgumentException.class,
                () -> new EtfBasicJobService.Plan(request, TARGET, OBSERVED.plusNanos(1000)));
        var nanos = request(Family.BASIC, OBSERVED.plusNanos(1));
        assertThrows(IllegalArgumentException.class, () -> plan(Family.BASIC, nanos, TARGET));
        var dated = EtfBasicSyncJobOwner.DEFINITION.freeze(Mode.SNAPSHOT,
                Map.of("targetId", TARGET, "observedAt", OBSERVED.toString()), DATE, DATE, DATE);
        assertThrows(IllegalArgumentException.class, () -> plan(Family.BASIC, dated, TARGET));
        assertEquals(OBSERVED, EtfBasicSyncAdapter.observedAt(request));
    }

    @Test
    void basicRunChecksIdentityAgainAfterValidatePlanBeforeConstructingWriter() throws Exception {
        var h = new Harness(Family.BASIC, temp.resolve("identity.sqlite3"));
        when(h.target.targetId()).thenReturn(TARGET, OTHER);
        assertThrows(IllegalStateException.class, () -> h.run(h.plan()));
        verify(h.target, times(2)).targetId();
        verify(h.target, never()).newWriter(anyString());
        verifyNoInteractions(h.pages, h.calendars, h.jobs);
        assertFalse(Files.exists(h.ledger));
    }

    @ParameterizedTest @EnumSource(value = Family.class, names = {"PORTFOLIO", "SHARE"})
    void freshDateJobsRejectChangedBaselineBeforeLedgerAndWriter(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("baseline.sqlite3"));
        when(h.dateTarget().range()).thenReturn(new EtfTargetRange(DATE, DATE));
        assertThrows(IllegalStateException.class, () -> h.run(h.plan()));
        verify(h.dateTarget()).range();
        verify(h.target, never()).newWriter(anyString());
        verifyNoInteractions(h.pages, h.calendars, h.jobs);
        assertFalse(Files.exists(h.ledger));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void resumeReplaysObservationAndSkipsFreshRange(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("resume.sqlite3"));
        var request = request(family, OBSERVED);
        failedRun(h.ledger, request);
        if (family != Family.BASIC)
            when(h.dateTarget().range()).thenThrow(new AssertionError("Recovery must use the frozen baseline"));
        var result = h.resume(plan(family, request, TARGET));
        assertEquals(SyncRunState.FAILED, result.state()); // Deliberate writer preflight stop, before source I/O.
        assertEquals("IllegalStateException", result.errorCode());
        var saved = SyncRunLedger.openReadOnly(h.ledger).getRun(result.runId());
        assertEquals("prior", saved.parentRunId());
        assertEquals(SyncRequestIdentity.snapshotJson(request), saved.frozenJson());
        assertEquals(OBSERVED.toString(), JobDefinitionJson.mapper().readTree(saved.frozenJson())
                .path("parameters").path("observedAt").asText());
        verify(h.target).newWriter(TARGET);
        if (family != Family.BASIC) verify(h.dateTarget(), never()).range();
        verifyNoInteractions(h.pages, h.jobs);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void changedObservationCannotResumeAndBasicRetainsItsEarlierTargetCheck(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("observation.sqlite3"));
        failedRun(h.ledger, request(family, OBSERVED));
        var changed = plan(family, request(family, OBSERVED.plusSeconds(1)), TARGET);
        if (family == Family.BASIC) {
            assertThrows(IllegalStateException.class, () -> h.resume(changed));
            verify(h.target).targetId();
            verifyNoMoreInteractions(h.target);
        } else {
            assertThrows(IllegalArgumentException.class, () -> h.resume(changed));
            verifyNoInteractions(h.target);
        }
        verifyNoInteractions(h.pages, h.calendars, h.jobs, h.writer);
    }

    @Test
    void basicResumeRejectsRetainedWholeDatasetLeaseBeforeCreatingWriter() throws Exception {
        var h = new Harness(Family.BASIC, temp.resolve("lease.sqlite3"));
        failedRun(h.ledger, request(Family.BASIC, OBSERVED));
        var locks = new DatasetIntervalLock(h.ledger);
        assertNotNull(locks.acquire("prior", DatasetIntervalLock.Scope.allDates("etf_basic")));
        var failure = assertThrows(IllegalStateException.class, () -> h.resume(h.plan()));
        assertEquals("Only a terminal failed/cancelled/partial exact-plan etf_basic run can resume", failure.getMessage());
        verify(h.target, never()).newWriter(anyString());
        verifyNoInteractions(h.pages, h.calendars, h.jobs, h.writer);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void eachAttemptConstructsItsOwnWriterSourceAndEvidencePath(Family family) throws Exception {
        var h = new Harness(family, temp.resolve("attempts.sqlite3"));
        var writers = List.of(writer(), writer());
        var created = new AtomicInteger();
        when(h.target.newWriter(TARGET)).thenAnswer(invocation -> {
            assertTrue(Files.isRegularFile(h.ledger));
            return writers.get(created.getAndIncrement());
        });
        var sourcePaths = new ArrayList<Path>();
        try (var sources = mockConstruction(family.source, (source, context) -> {
            assertEquals(sourcePaths.size() + 1, created.get());
            sourcePaths.add((Path) context.arguments().get(family == Family.BASIC ? 1 : 2));
        })) {
            var first = h.run(h.plan());
            var second = h.run(h.plan());
            assertEquals(SyncRunState.FAILED, first.state());
            assertEquals(SyncRunState.FAILED, second.state());
            assertNotEquals(first.runId(), second.runId());
            assertEquals(List.of(h.ledger.getParent().resolve("sync-evidence").resolve(first.runId()).resolve("source"),
                    h.ledger.getParent().resolve("sync-evidence").resolve(second.runId()).resolve("source")), sourcePaths);
            assertEquals(2, sources.constructed().size());
            assertNotSame(sources.constructed().getFirst(), sources.constructed().getLast());
        }
        verify(h.target, times(2)).newWriter(TARGET);
        for (var writer : writers) verify(writer).preflight();
        verifyNoInteractions(h.pages, h.jobs);
    }

    @Test
    void portfolioUsesAllCalendarDaysAndFortyFiveDayBoundWhileShareAllowsNoTradingSessions() {
        var portfolio = request(Family.PORTFOLIO, OBSERVED);
        assertEquals(List.of(DATE), EtfPortfolioSyncAdapter.decodeAnnouncementDates(portfolio));
        assertEquals(List.of(), EtfShareSyncAdapter.decodeTradeDates(request(Family.SHARE, OBSERVED)));
        assertEquals(45, EtfPortfolioSyncJobOwner.DEFINITION.budget().maxWindowDays());
        assertEquals(366, EtfShareSyncJobOwner.DEFINITION.budget().maxWindowDays());
        var missingSunday = EtfPortfolioSyncJobOwner.DEFINITION.freeze(Mode.BACKFILL,
                Map.of("targetId", TARGET, "observedAt", OBSERVED.toString(), "ann_dates", "20200104"),
                DATE, DATE.plusDays(1), DATE.plusDays(1));
        assertThrows(IllegalArgumentException.class,
                () -> EtfPortfolioSyncAdapter.decodeAnnouncementDates(missingSunday));
    }

    @Test @SuppressWarnings("unchecked")
    void basicChunkFingerprintUsesSessionCodecAndKeepsByteFraming() throws Exception {
        VerifiedWriteSession<EtfBasic, EtfBasicKey> writer = mock(VerifiedWriteSession.class);
        VerifiedBatchExecutor.Codec<EtfBasic, EtfBasicKey> codec = mock(VerifiedBatchExecutor.Codec.class);
        when(writer.codec()).thenReturn(codec);
        var first = mock(EtfBasic.class); var second = mock(EtfBasic.class);
        when(codec.canonicalBytes(first)).thenReturn(new byte[]{1, 0, 2});
        when(codec.canonicalBytes(second)).thenReturn(new byte[]{3, 4});
        var adapter = new EtfBasicSyncAdapter(mock(TusharePageService.class), writer, temp);
        var fingerprint = EtfBasicSyncAdapter.class.getDeclaredMethod("chunkFingerprint", String.class, int.class, List.class);
        fingerprint.setAccessible(true);
        byte[] framed = new byte[]{'s', 'o', 'u', 'r', 'c', 'e', 0, '2', 0, 0, 0, 3, 1, 0, 2, 0, 0, 0, 2, 3, 4};
        String expected = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(framed));
        assertEquals(expected, fingerprint.invoke(adapter, "source", 2, List.of(first, second)));
        assertSame(codec, adapter.codec());
        verify(codec).canonicalBytes(first); verify(codec).canonicalBytes(second);
    }

    @Test
    void sharedRowCapsKeepExactPublicSourceValues() {
        assertEquals(1_000_000, EtfPortfolioSource.MAX_ROWS_PER_ANN_DATE);
        assertEquals(EtfPortfolioDataset.MAX_ROWS_PER_ANN_DATE, EtfPortfolioSource.MAX_ROWS_PER_ANN_DATE);
        assertEquals(5997, EtfShareSource.MAX_ROWS_PER_DATE);
        assertEquals(EtfShareDataset.MAX_ROWS_PER_DATE, EtfShareSource.MAX_ROWS_PER_DATE);
    }

    private static FrozenRequest request(Family family, Instant observedAt) {
        var parameters = new java.util.LinkedHashMap<String, Object>();
        parameters.put("targetId", TARGET); parameters.put("observedAt", observedAt.toString());
        if (family == Family.PORTFOLIO) parameters.put("ann_dates", "20200104");
        if (family == Family.SHARE) parameters.put("trade_dates", "NONE");
        return family.definition.freeze(family == Family.BASIC ? Mode.SNAPSHOT : Mode.BACKFILL,
                parameters, family == Family.BASIC ? null : DATE, family == Family.BASIC ? null : DATE, DATE);
    }

    private static Object plan(Family family, FrozenRequest request, String target) {
        return switch (family) {
            case BASIC -> new EtfBasicJobService.Plan(request, target, Instant.parse((String) request.parameters().get("observedAt")));
            case PORTFOLIO -> new EtfPortfolioJobService.Plan(request, target, null, null, null, null, false);
            case SHARE -> new EtfShareJobService.Plan(request, target, null, null, null, null, false);
        };
    }

    private static void failedRun(Path ledgerPath, FrozenRequest request) throws Exception {
        var ledger = new SyncRunLedger(ledgerPath);
        ledger.createRun("prior", null, TARGET, request);
        ledger.transition("prior", 0, SyncRunState.FAILED, "{}");
    }

    @SuppressWarnings("unchecked")
    private static EtfWriteSession<Object, Object> writer() {
        EtfWriteSession<Object, Object> writer = mock(EtfWriteSession.class);
        doThrow(new IllegalStateException("Stop before source I/O")).when(writer).preflight();
        return writer;
    }

    private static final class Harness {
        final Family family;
        final Path ledger;
        final SyncJobRegistry jobs = mock(SyncJobRegistry.class);
        final TusharePageService pages = mock(TusharePageService.class);
        final ExchangeCalendarReadRepository calendars = mock(ExchangeCalendarReadRepository.class);
        final EtfWriteTarget<Object, Object> target;
        final EtfWriteSession<Object, Object> writer = writer();
        final Object service;

        @SuppressWarnings("unchecked")
        Harness(Family family, Path ledger) {
            this.family = family; this.ledger = ledger;
            target = family == Family.BASIC ? mock(EtfWriteTarget.class) : mock(EtfTarget.class);
            when(target.tableName()).thenReturn(family.table);
            when(target.targetId()).thenReturn(TARGET);
            if (family != Family.BASIC) when(dateTarget().range()).thenReturn(new EtfTargetRange(null, null));
            when(target.newWriter(TARGET)).thenReturn(writer);
            when(calendars.findPage(any())).thenReturn(new DatasetReadPage<>("exchange_calendar", 1, null,
                    Instant.EPOCH, List.of(new ExchangeCalendar("SSE", DATE, false, null)), null));
            service = switch (family) {
                case BASIC -> new EtfBasicJobService(jobs, pages, typedWriteTarget(), ledger);
                case PORTFOLIO -> new EtfPortfolioJobService(jobs, pages, typedDateTarget(), ledger);
                case SHARE -> new EtfShareJobService(jobs, pages, calendars, typedDateTarget(), ledger);
            };
            clearInvocations(target);
        }
        EtfTarget<Object, Object> dateTarget() { return (EtfTarget<Object, Object>) target; }
        @SuppressWarnings("unchecked") private <T, K> EtfWriteTarget<T, K> typedWriteTarget() {
            return (EtfWriteTarget<T, K>) (EtfWriteTarget<?, ?>) target;
        }
        @SuppressWarnings("unchecked") private <T, K> EtfTarget<T, K> typedDateTarget() {
            return (EtfTarget<T, K>) (EtfTarget<?, ?>) dateTarget();
        }
        Object plan() { return EtfOtherJobServiceContractTest.plan(family, request(family, OBSERVED), TARGET); }
        SyncJobRunner.Result run(Object plan) throws Exception {
            return switch (family) {
                case BASIC -> ((EtfBasicJobService) service).run((EtfBasicJobService.Plan) plan);
                case PORTFOLIO -> ((EtfPortfolioJobService) service).run((EtfPortfolioJobService.Plan) plan);
                case SHARE -> ((EtfShareJobService) service).run((EtfShareJobService.Plan) plan);
            };
        }
        SyncJobRunner.Result resume(Object plan) throws Exception {
            return switch (family) {
                case BASIC -> ((EtfBasicJobService) service).resume((EtfBasicJobService.Plan) plan, "prior");
                case PORTFOLIO -> ((EtfPortfolioJobService) service).resume((EtfPortfolioJobService.Plan) plan, "prior");
                case SHARE -> ((EtfShareJobService) service).resume((EtfShareJobService.Plan) plan, "prior");
            };
        }
    }
}
