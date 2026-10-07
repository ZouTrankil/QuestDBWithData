package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.RetailSentimentDailyV1;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.repository.RetailSentimentDailyV1MaterializationPort;
import com.zoutrankil.data.repository.RetailSentimentDailyV1MaterializationPort.Snapshot;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Admission, complete source coverage and terminal version guards without a live database. */
class RetailSentimentDailyV1MaterializeAdapterTest {
    private static final LocalDate FROM = LocalDate.of(2026, 9, 17);
    private static final LocalDate TO = FROM.plusDays(1);
    private static final String SOURCE_VERSION = "12:8";
    private static final String CALENDAR_VERSION = "calendar-5:9:9:fixed-schema";
    private static final String TARGET_ID = "questdb-" + "a".repeat(64);
    private static final List<RetailSentimentDailyV1> DAYS = List.of(
            new RetailSentimentDailyV1(FROM, 0.5, 1.2, 3.5, -1.0, 0.6, 2L, 1L, 0.2, 1L, 3L, 51.0, -2.0),
            new RetailSentimentDailyV1(TO, null, 1.8, 0.4, -0.1, 0.7, 3L, 2L, null, 0L, null, 48.0, -0.3));
    private final RetailSentimentDailyV1MaterializationPort port = mock(RetailSentimentDailyV1MaterializationPort.class);
    private final Snapshot frozen = readySnapshot();
    private RetailSentimentDailyV1MaterializeAdapter adapter;
    private FrozenRequest request;

    @BeforeEach void setup() {
        when(frozen.sourceVersion()).thenReturn(SOURCE_VERSION);
        when(frozen.sourceUnchanged(frozen)).thenReturn(true);
        when(port.targetId()).thenReturn(TARGET_ID);
        when(port.calendarVersion()).thenReturn(CALENDAR_VERSION);
        when(port.snapshot()).thenReturn(frozen);
        when(port.sourceRawRows(FROM, TO)).thenReturn(5L);
        when(port.expected(FROM, TO)).thenReturn(DAYS);
        when(port.verifiedSnapshot()).thenReturn(frozen);
        adapter = new RetailSentimentDailyV1MaterializeAdapter(port, frozen);
        request = freeze(SOURCE_VERSION, TARGET_ID, FROM, TO);
    }

    private static Snapshot readySnapshot() {
        Snapshot snapshot = mock(Snapshot.class);
        when(snapshot.valid()).thenReturn(true);
        when(snapshot.caughtUp()).thenReturn(true);
        when(snapshot.sourceSettled()).thenReturn(true);
        when(snapshot.mvSettled()).thenReturn(true);
        when(snapshot.mvId()).thenReturn(22L);
        when(snapshot.mvDirectory()).thenReturn("mv_retail_sentiment_daily_v1~22");
        when(snapshot.definitionSha()).thenReturn(RetailSentimentDailyV1MaterializationPort.DEFINITION_SHA);
        return snapshot;
    }

    private static FrozenRequest freeze(String version, String target, LocalDate from, LocalDate to) {
        return RetailSentimentDailyV1JobService.definition().freeze(null, Map.of(
                "source_version", version, "target_id", target, "bootstrap_from", from,
                "calendar_version", CALENDAR_VERSION), from, to, TO);
    }

    private static FrozenRequest explicitMaterializeRequest() {
        return RetailSentimentDailyV1JobService.definition().freeze(Mode.MATERIALIZE,
                Map.of("source_version", SOURCE_VERSION, "target_id", TARGET_ID, "bootstrap_from", FROM),
                FROM, TO, TO);
    }

    @Test void defaultIncrementalDefinitionHasFiniteSinglePageBudget() {
        var definition = RetailSentimentDailyV1JobService.definition();
        assertEquals(Mode.INCREMENTAL, definition.defaultMode());
        assertEquals(31, definition.budget().maxWindowDays());
        assertEquals(1, definition.budget().maxSlices());
        assertEquals(1, definition.budget().maxPages());
        assertEquals(31, definition.budget().maxRows());
        assertEquals(64 * 1024, definition.budget().maxBatchBytes());
        assertEquals(1, definition.retry().maxAttempts());
        assertEquals(List.of(new SyncJobDefinition.JobRef(L2DailyFeaturesJobService.definition().jobId(), L2DailyFeaturesJobService.definition().version()),
                new SyncJobDefinition.JobRef("data.exchange_calendar", 1)), definition.dependencies());
        assertEquals(Mode.INCREMENTAL, request.mode());
        assertEquals(SOURCE_VERSION, request.parameters().get("source_version"));
        assertEquals(TARGET_ID, request.parameters().get("target_id"));
        assertEquals(CALENDAR_VERSION, request.parameters().get("calendar_version"));
    }

    @Test void thirtyOneDayWindowIsAcceptedAndThirtyTwoDaysIsRejectedBeforePortAccess() {
        assertEquals(FROM.plusDays(30), freeze(SOURCE_VERSION, TARGET_ID, FROM, FROM.plusDays(30)).to());
        assertThrows(IllegalArgumentException.class,
                () -> freeze(SOURCE_VERSION, TARGET_ID, FROM, FROM.plusDays(31)));
        verifyNoInteractions(port);
    }

    @Test void changedFrozenSourceVersionAndPhysicalTargetAreRejectedBeforeBinding() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.preflight(freeze("12:9", TARGET_ID, FROM, TO)));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.preflight(freeze(SOURCE_VERSION, "questdb-" + "b".repeat(64), FROM, TO)));
        verify(port, never()).bind(any(), any(), any());
        verify(port, never()).preflight();
        verify(port, never()).expected(any(), any());
    }

    @Test void cancellationBeforeFetchPreventsSourceReadsAndConsumerInvocation() {
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDailyV1>>();
        assertThrows(CancellationException.class, () -> adapter.fetch(request, pages::add, () -> true));
        assertTrue(pages.isEmpty());
        verify(port, never()).bind(any(), any(), any());
        verify(port, never()).snapshot();
        verify(port, never()).sourceRawRows(any(), any());
        verify(port, never()).expected(any(), any());
    }

    @Test void sourceChangedAfterPlanningIsRejectedAtPreflight() {
        Snapshot changed = readySnapshot();
        when(port.snapshot()).thenReturn(changed);
        var error = assertThrows(IllegalStateException.class, () -> adapter.preflight(request));
        assertTrue(error.getMessage().contains("source changed after planning"));
        verify(port).bind(FROM, TO, frozen);
        verify(port).preflight();
        verify(port, never()).expected(any(), any());
    }

    @Test void sourceChangedDuringCompleteAggregationCannotDeliverAPage() {
        Snapshot changed = readySnapshot();
        when(port.snapshot()).thenReturn(frozen, changed);
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDailyV1>>();
        var error = assertThrows(IllegalStateException.class,
                () -> adapter.fetch(request, pages::add, () -> false));
        assertTrue(error.getMessage().contains("source changed while reading"));
        assertTrue(pages.isEmpty());
        verify(port, never()).send(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "source-unsettled", "mv-unsettled"})
    void invalidOrUnsettledSnapshotIsRejectedBeforeReadingSource(String condition) {
        if (condition.equals("invalid")) when(frozen.valid()).thenReturn(false);
        if (condition.equals("source-unsettled")) when(frozen.sourceSettled()).thenReturn(false);
        if (condition.equals("mv-unsettled")) when(frozen.mvSettled()).thenReturn(false);
        assertThrows(IllegalStateException.class,
                () -> adapter.fetch(request, page -> fail("No page may escape an unreadable snapshot"), () -> false));
        verify(port, never()).sourceRawRows(any(), any());
        verify(port, never()).expected(any(), any());
    }

    @Test void validSettledLaggingMvCanCatchUpAfterFullValueReadbackAndComplete() throws Exception {
        when(frozen.caughtUp()).thenReturn(false);
        Snapshot caughtUp = readySnapshot();
        when(frozen.sourceUnchanged(caughtUp)).thenReturn(true);
        when(port.snapshot()).thenReturn(frozen, frozen, caughtUp);
        when(port.verifiedSnapshot()).thenReturn(caughtUp);
        when(port.readback(List.of(FROM, TO))).thenReturn(DAYS);
        when(port.walSettled()).thenReturn(true);
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDailyV1>>();
        var completion = adapter.fetch(request, page -> {
            pages.add(page);
            port.send(page.rows());
            var actual = port.readback(page.rows().stream().map(RetailSentimentDailyV1::tradeDate).toList());
            assertEquals(page.rows().size(), actual.size());
            for (int index = 0; index < actual.size(); index++)
                assertTrue(adapter.codec().equivalent(page.rows().get(index), actual.get(index)));
            assertTrue(port.walSettled());
        }, () -> false);
        assertEquals(1, pages.size());
        assertEquals(DAYS, pages.getFirst().rows());
        assertTrue(completion.complete());
        assertEquals(2, completion.rows());
        assertEquals(5, adapter.sourceRawRows());
        assertSame(caughtUp, adapter.lastVerificationSnapshot());
        verify(port).send(DAYS);
        verify(port).readback(List.of(FROM, TO));
        verify(port).walSettled();
    }

    @Test void mvThatRemainsLaggingAfterConsumerCannotReturnComplete() {
        when(frozen.caughtUp()).thenReturn(false);
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDailyV1>>();
        assertThrows(IllegalStateException.class,
                () -> adapter.fetch(request, pages::add, () -> false));
        assertEquals(1, pages.size());
        assertEquals(DAYS, pages.getFirst().rows());
        assertEquals(5, adapter.sourceRawRows());
        assertNull(adapter.lastVerificationSnapshot());
        verify(port, never()).verifiedSnapshot();
    }

    @Test void nonemptyDailyPageCoversEveryRawRowAndCarriesFrozenCompletenessEvidence() throws Exception {
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDailyV1>>();
        var completion = adapter.fetch(request, pages::add, () -> false);
        assertEquals(1, pages.size());
        assertEquals(DAYS, pages.getFirst().rows());
        assertTrue(pages.getFirst().sourceFingerprint().matches("[0-9a-f]{64}"));
        var evidence = new ObjectMapper().readTree(pages.getFirst().responseEvidence());
        assertEquals(5, evidence.path("sourceRawRows").asLong());
        assertEquals(2, evidence.path("sourceAggregateRows").asInt());
        assertTrue(evidence.path("sourceComplete").asBoolean());
        assertEquals(CALENDAR_VERSION, evidence.path("calendarVersion").asText());
        assertEquals(FROM.toString(), evidence.path("fromInclusive").asText());
        assertEquals(TO.plusDays(1).toString(), evidence.path("toExclusive").asText());
        verify(port).requireSourceCoverage(FROM, TO, 5L, DAYS);
        assertEquals(5, adapter.sourceRawRows());
        assertEquals(1, completion.pages());
        assertEquals(2, completion.rows());
        assertTrue(completion.complete());
        assertSame(frozen, adapter.lastVerificationSnapshot());
    }

    @Test void independentSourceCensusMustMatchBeforeDeliveringACompletePage() {
        doThrow(new IllegalStateException("D098 source census does not cover all source rows"))
                .when(port).requireSourceCoverage(FROM, TO, 5L, DAYS);
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDailyV1>>();
        var error = assertThrows(IllegalStateException.class,
                () -> adapter.fetch(request, pages::add, () -> false));
        assertTrue(error.getMessage().contains("does not cover all source rows"));
        assertTrue(pages.isEmpty());
        verify(port, never()).send(any());
    }

    @Test void incrementalRequestMustFreezeItsIndependentCalendarVersion() {
        var withoutCalendar = RetailSentimentDailyV1JobService.definition().freeze(Mode.INCREMENTAL,
                Map.of("source_version", SOURCE_VERSION, "target_id", TARGET_ID, "bootstrap_from", FROM),
                FROM, TO, TO);
        assertThrows(IllegalArgumentException.class, () -> adapter.preflight(withoutCalendar));
        verify(port, never()).bind(any(), any(), any());
    }

    @Test void calendarChangedAfterPlanningIsRejectedAtPreflight() {
        when(port.calendarVersion()).thenReturn("calendar-5:10:10:fixed-schema");
        var error = assertThrows(IllegalStateException.class, () -> adapter.preflight(request));
        assertTrue(error.getMessage().contains("calendar differs from the frozen incremental version"));
        verify(port, never()).expected(any(), any());
    }

    @Test void calendarChangedDuringCoveragePreventsPageDelivery() {
        when(port.calendarVersion()).thenReturn(CALENDAR_VERSION, "calendar-5:10:10:fixed-schema");
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDailyV1>>();
        var error = assertThrows(IllegalStateException.class,
                () -> adapter.fetch(request, pages::add, () -> false));
        assertTrue(error.getMessage().contains("calendar differs from the frozen incremental version"));
        verify(port).requireCalendarCoverage(FROM, TO, List.of(FROM, TO));
        assertTrue(pages.isEmpty());
        verify(port, never()).send(any());
    }

    @Test void calendarChangedAfterActualReadbackCannotReturnSourceComplete() {
        when(port.calendarVersion()).thenReturn(CALENDAR_VERSION, CALENDAR_VERSION,
                "calendar-5:10:10:fixed-schema");
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDailyV1>>();
        var error = assertThrows(IllegalStateException.class,
                () -> adapter.fetch(request, pages::add, () -> false));
        assertTrue(error.getMessage().contains("calendar differs from the frozen incremental version"));
        assertEquals(1, pages.size());
        verify(port).verifiedSnapshot();
    }

    @Test void missingTradingDayBucketIsRejectedEvenWhenAllPresentRawRowsAreCovered() {
        when(port.sourceRawRows(FROM, TO)).thenReturn(2L);
        when(port.expected(FROM, TO)).thenReturn(List.of(DAYS.getFirst()));
        doThrow(new IllegalStateException("D098 source daily buckets do not cover the complete real SSE trading calendar"))
                .when(port).requireCalendarCoverage(FROM, TO, List.of(FROM));
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDailyV1>>();
        var error = assertThrows(IllegalStateException.class,
                () -> adapter.fetch(request, pages::add, () -> false));
        assertTrue(error.getMessage().contains("complete real SSE trading calendar"));
        verify(port).requireCalendarCoverage(FROM, TO, List.of(FROM));
        assertTrue(pages.isEmpty());
        verify(port, never()).send(any());
    }

    @Test void emptySourceWithStaleOutputIsRejectedBeforePageDelivery() {
        request = explicitMaterializeRequest();
        when(port.sourceRawRows(FROM, TO)).thenReturn(0L);
        when(port.expected(FROM, TO)).thenReturn(List.of());
        when(port.actual(FROM, TO)).thenReturn(DAYS);
        var error = assertThrows(IllegalStateException.class,
                () -> adapter.fetch(request, page -> fail("Stale output cannot become verified empty"), () -> false));
        assertTrue(error.getMessage().contains("empty source has stale output"));
        verify(port).actual(FROM, TO);
        verify(port, never()).send(any());
    }

    @Test void genuinelyEmptyStableSourceCompletesWithoutRefresh() throws Exception {
        request = explicitMaterializeRequest();
        when(port.sourceRawRows(FROM, TO)).thenReturn(0L);
        when(port.expected(FROM, TO)).thenReturn(List.of());
        when(port.actual(FROM, TO)).thenReturn(List.of());
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDailyV1>>();
        var completion = adapter.fetch(request, pages::add, () -> false);
        assertEquals(1, pages.size());
        assertTrue(pages.getFirst().rows().isEmpty());
        assertEquals(0, completion.rows());
        assertTrue(completion.complete());
        verify(port, never()).send(any());
        verify(port, never()).verifiedSnapshot();
    }

    @Test void mvChangedAfterReadbackCannotReturnSourceComplete() {
        Snapshot changedOutput = readySnapshot();
        when(frozen.sourceUnchanged(changedOutput)).thenReturn(true);
        when(port.snapshot()).thenReturn(frozen, frozen, changedOutput);
        var error = assertThrows(IllegalStateException.class,
                () -> adapter.fetch(request, page -> {}, () -> false));
        assertTrue(error.getMessage().contains("MV version changed after its actual readback verification"));
    }

    @Test void cancellationAfterPageVerificationPreventsCompletion() {
        var calls = new AtomicInteger();
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDailyV1>>();
        assertThrows(CancellationException.class,
                () -> adapter.fetch(request, pages::add, () -> calls.getAndIncrement() >= 2));
        assertEquals(1, pages.size());
        verify(port, never()).verifiedSnapshot();
    }

    @Test void nativeRefreshExclusionCoversAllDatesEvenForDifferentBoundedWindows() {
        assertEquals(DatasetIntervalLock.Scope.allDates("mv_retail_sentiment_daily_v1"),
                adapter.conflictScope(request));
        var other = freeze(SOURCE_VERSION, TARGET_ID, FROM.plusDays(7), TO.plusDays(7));
        assertEquals(adapter.conflictScope(request), adapter.conflictScope(other));
    }

    @Test void nullableLongCodecDistinguishesNullFromZeroAndPreservesLongMaxExactly() {
        var missing = new RetailSentimentDailyV1(FROM, null, null, null, null, null,
                null, null, null, null, null, null, null);
        var zero = new RetailSentimentDailyV1(FROM, null, null, null, null, null,
                0L, null, null, null, null, null, null);
        var max = new RetailSentimentDailyV1(FROM, null, null, null, null, null,
                Long.MAX_VALUE, null, null, null, null, null, null);
        assertEquals(116, adapter.codec().canonicalBytes(missing).length);
        assertFalse(java.util.Arrays.equals(adapter.codec().canonicalBytes(missing), adapter.codec().canonicalBytes(zero)));
        assertFalse(java.util.Arrays.equals(adapter.codec().canonicalBytes(max), adapter.codec().canonicalBytes(zero)));
        assertFalse(adapter.codec().equivalent(missing, zero));
        assertTrue(adapter.codec().equivalent(max, max));
    }

    @Test void numericReadbackTolerancePreservesSignedAmountsAndExactNullableCounts() {
        var expected = DAYS.getFirst();
        var rounded = new RetailSentimentDailyV1(expected.tradeDate(), expected.avgRetailRatio(),
                expected.avgRetailEntropy(), expected.totalRetailAmountYi(), expected.totalRetailNetInflowYi() + 1e-10,
                expected.avgRelAggro(), expected.totalQ1(), expected.totalQ3(), expected.avgWashTradeRatio(),
                expected.totalSpoofCount(), expected.totalManipulationCount(), expected.avgMfiScore(), expected.totalMainNetYi());
        assertTrue(adapter.codec().equivalent(expected, rounded));
        var wrongCount = new RetailSentimentDailyV1(rounded.tradeDate(), rounded.avgRetailRatio(), rounded.avgRetailEntropy(),
                rounded.totalRetailAmountYi(), rounded.totalRetailNetInflowYi(), rounded.avgRelAggro(),
                rounded.totalQ1() + 1, rounded.totalQ3(), rounded.avgWashTradeRatio(), rounded.totalSpoofCount(),
                rounded.totalManipulationCount(), rounded.avgMfiScore(), rounded.totalMainNetYi());
        assertFalse(adapter.codec().equivalent(expected, wrongCount));
        var beyond = new RetailSentimentDailyV1(expected.tradeDate(), expected.avgRetailRatio(),
                expected.avgRetailEntropy(), expected.totalRetailAmountYi(), expected.totalRetailNetInflowYi() + 1e-3,
                expected.avgRelAggro(), expected.totalQ1(), expected.totalQ3(), expected.avgWashTradeRatio(),
                expected.totalSpoofCount(), expected.totalManipulationCount(), expected.avgMfiScore(), expected.totalMainNetYi());
        assertFalse(adapter.codec().equivalent(expected, beyond));
    }

    @Test void reconciliationReadbackChecksActualValuesWithoutAnotherSend() throws Exception {
        when(port.readback(List.of(FROM, TO))).thenReturn(DAYS);
        when(port.walSettled()).thenReturn(true);
        assertTrue(adapter.revalidateOnly(request, () -> false).complete());
        verify(port).readback(List.of(FROM, TO));
        verify(port).walSettled();
        verify(port, never()).send(any());
    }
}
