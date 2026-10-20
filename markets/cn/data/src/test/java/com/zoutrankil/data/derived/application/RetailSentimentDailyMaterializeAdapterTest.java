package com.zoutrankil.data.derived.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.derived.port.RetailSentimentDailySession;
import com.zoutrankil.data.derived.storage.RetailSentimentDailyMaterializationPort;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.derived.RetailSentimentDaily;
import com.zoutrankil.data.domain.derived.RetailSentimentDailySnapshot;
import com.zoutrankil.data.l2.application.L2DailyFeaturesJobService;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.SyncJobRunner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Admission, complete source coverage and terminal version guards without a live database. */
class RetailSentimentDailyMaterializeAdapterTest {
    private static final LocalDate FROM = LocalDate.of(2026, 9, 17);
    private static final LocalDate TO = FROM.plusDays(1);
    private static final String SOURCE_VERSION = "12:8";
    private static final String CALENDAR_VERSION = "calendar-5:9:9:fixed-schema";
    private static final String TARGET_ID = "questdb-" + "a".repeat(64);
    private static final List<RetailSentimentDaily> DAYS =
            List.of(
                    new RetailSentimentDaily(
                            FROM, 0.5, 1.2, 3.5, -1.0, 0.6, 2L, 1L, 0.2, 1L, 3L, 51.0, -2.0),
                    new RetailSentimentDaily(
                            TO, null, 1.8, 0.4, -0.1, 0.7, 3L, 2L, null, 0L, null, 48.0, -0.3));
    private final RetailSentimentDailySession port = mock(RetailSentimentDailySession.class);
    private final RetailSentimentDailySnapshot frozen = readySnapshot();
    private RetailSentimentDailyMaterializeAdapter adapter;
    private FrozenRequest request;

    @BeforeEach
    void setup() {
        when(frozen.sourceVersion()).thenReturn(SOURCE_VERSION);
        when(frozen.sourceUnchanged(frozen)).thenReturn(true);
        when(port.targetId()).thenReturn(TARGET_ID);
        when(port.calendarVersion()).thenReturn(CALENDAR_VERSION);
        when(port.newCalendarReader())
                .thenReturn(query -> MaterializationCalendarFixture.page(query, List.of(FROM, TO)));
        when(port.snapshot()).thenReturn(frozen);
        when(port.sourceRawRows(FROM, TO)).thenReturn(5L);
        when(port.expected(FROM, TO)).thenReturn(DAYS);
        when(port.verifiedSnapshot()).thenReturn(frozen);
        adapter = new RetailSentimentDailyMaterializeAdapter(port, frozen);
        request = freeze(SOURCE_VERSION, TARGET_ID, FROM, TO);
    }

    private static RetailSentimentDailySnapshot readySnapshot() {
        RetailSentimentDailySnapshot snapshot = mock(RetailSentimentDailySnapshot.class);
        when(snapshot.valid()).thenReturn(true);
        when(snapshot.caughtUp()).thenReturn(true);
        when(snapshot.sourceSettled()).thenReturn(true);
        when(snapshot.mvSettled()).thenReturn(true);
        when(snapshot.mvId()).thenReturn(22L);
        when(snapshot.mvDirectory()).thenReturn("mv_retail_sentiment_daily~22");
        when(snapshot.definitionSha())
                .thenReturn(RetailSentimentDailyMaterializationPort.DEFINITION_SHA);
        return snapshot;
    }

    private static FrozenRequest freeze(
            String version, String target, LocalDate from, LocalDate to) {
        return RetailSentimentDailyJobService.definition()
                .freeze(
                        null,
                        Map.of(
                                "source_version",
                                version,
                                "target_id",
                                target,
                                "bootstrap_from",
                                from,
                                "calendar_version",
                                CALENDAR_VERSION),
                        from,
                        to,
                        TO);
    }

    private static FrozenRequest explicitMaterializeRequest() {
        return RetailSentimentDailyJobService.definition()
                .freeze(
                        Mode.MATERIALIZE,
                        Map.of(
                                "source_version",
                                SOURCE_VERSION,
                                "target_id",
                                TARGET_ID,
                                "bootstrap_from",
                                FROM),
                        FROM,
                        TO,
                        TO);
    }

    @Test
    void defaultIncrementalDefinitionHasFiniteSinglePageBudget() {
        var definition = RetailSentimentDailyJobService.definition();
        assertEquals(Mode.INCREMENTAL, definition.defaultMode());
        assertEquals(31, definition.budget().maxWindowDays());
        assertEquals(1, definition.budget().maxSlices());
        assertEquals(1, definition.budget().maxPages());
        assertEquals(31, definition.budget().maxRows());
        assertEquals(64 * 1024, definition.budget().maxBatchBytes());
        assertEquals(1, definition.retry().maxAttempts());
        assertEquals(
                List.of(
                        new SyncJobDefinition.JobRef(
                                L2DailyFeaturesJobService.definition().jobId(),
                                L2DailyFeaturesJobService.definition().version()),
                        new SyncJobDefinition.JobRef("data.exchange_calendar", 1)),
                definition.dependencies());
        assertEquals(Mode.INCREMENTAL, request.mode());
        assertEquals(SOURCE_VERSION, request.parameters().get("source_version"));
        assertEquals(TARGET_ID, request.parameters().get("target_id"));
        assertEquals(CALENDAR_VERSION, request.parameters().get("calendar_version"));
    }

    @Test
    void thirtyOneDayWindowIsAcceptedAndThirtyTwoDaysIsRejectedBeforePortAccess() {
        assertEquals(
                FROM.plusDays(30), freeze(SOURCE_VERSION, TARGET_ID, FROM, FROM.plusDays(30)).to());
        assertThrows(
                IllegalArgumentException.class,
                () -> freeze(SOURCE_VERSION, TARGET_ID, FROM, FROM.plusDays(31)));
        verifyNoInteractions(port);
    }

    @Test
    void changedFrozenSourceVersionAndPhysicalTargetAreRejectedBeforeBinding() {
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.preflight(freeze("12:9", TARGET_ID, FROM, TO)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        adapter.preflight(
                                freeze(SOURCE_VERSION, "questdb-" + "b".repeat(64), FROM, TO)));
        verify(port, never()).bind(any(), any(), any());
        verify(port, never()).preflight();
        verify(port, never()).expected(any(), any());
    }

    @Test
    void cancellationBeforeFetchPreventsSourceReadsAndConsumerInvocation() {
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDaily>>();
        assertThrows(
                CancellationException.class, () -> adapter.fetch(request, pages::add, () -> true));
        assertTrue(pages.isEmpty());
        verify(port, never()).bind(any(), any(), any());
        verify(port, never()).snapshot();
        verify(port, never()).sourceRawRows(any(), any());
        verify(port, never()).expected(any(), any());
    }

    @Test
    void sourceChangedAfterPlanningIsRejectedAtPreflight() {
        RetailSentimentDailySnapshot changed = readySnapshot();
        when(port.snapshot()).thenReturn(changed);
        var error = assertThrows(IllegalStateException.class, () -> adapter.preflight(request));
        assertTrue(error.getMessage().contains("source changed after planning"));
        verify(port).bind(FROM, TO, frozen);
        verify(port).preflight();
        verify(port, never()).expected(any(), any());
    }

    @Test
    void sourceChangedDuringCompleteAggregationCannotDeliverAPage() {
        RetailSentimentDailySnapshot changed = readySnapshot();
        when(port.snapshot()).thenReturn(frozen, changed);
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDaily>>();
        var error =
                assertThrows(
                        IllegalStateException.class,
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
        assertThrows(
                IllegalStateException.class,
                () ->
                        adapter.fetch(
                                request,
                                page -> fail("No page may escape an unreadable snapshot"),
                                () -> false));
        verify(port, never()).sourceRawRows(any(), any());
        verify(port, never()).expected(any(), any());
    }

    @Test
    void validSettledLaggingMvCanCatchUpAfterFullValueReadbackAndComplete() throws Exception {
        when(frozen.caughtUp()).thenReturn(false);
        RetailSentimentDailySnapshot caughtUp = readySnapshot();
        when(frozen.sourceUnchanged(caughtUp)).thenReturn(true);
        when(port.snapshot()).thenReturn(frozen, frozen, caughtUp);
        when(port.verifiedSnapshot()).thenReturn(caughtUp);
        when(port.readback(List.of(FROM, TO))).thenReturn(DAYS);
        when(port.walSettled()).thenReturn(true);
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDaily>>();
        var completion =
                adapter.fetch(
                        request,
                        page -> {
                            pages.add(page);
                            port.send(page.rows());
                            var actual =
                                    port.readback(
                                            page.rows().stream()
                                                    .map(RetailSentimentDaily::tradeDate)
                                                    .toList());
                            assertEquals(page.rows().size(), actual.size());
                            for (int index = 0; index < actual.size(); index++)
                                assertTrue(
                                        adapter.codec()
                                                .equivalent(
                                                        page.rows().get(index), actual.get(index)));
                            assertTrue(port.walSettled());
                        },
                        () -> false);
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

    @Test
    void mvThatRemainsLaggingAfterConsumerCannotReturnComplete() {
        when(frozen.caughtUp()).thenReturn(false);
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDaily>>();
        assertThrows(
                IllegalStateException.class, () -> adapter.fetch(request, pages::add, () -> false));
        assertEquals(1, pages.size());
        assertEquals(DAYS, pages.getFirst().rows());
        assertEquals(5, adapter.sourceRawRows());
        assertNull(adapter.lastVerificationSnapshot());
        verify(port, never()).verifiedSnapshot();
    }

    @Test
    void nonemptyDailyPageCoversEveryRawRowAndCarriesFrozenCompletenessEvidence() throws Exception {
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDaily>>();
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

    @Test
    void independentSourceCensusMustMatchBeforeDeliveringACompletePage() {
        doThrow(new IllegalStateException("D098 source census does not cover all source rows"))
                .when(port)
                .requireSourceCoverage(FROM, TO, 5L, DAYS);
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDaily>>();
        var error =
                assertThrows(
                        IllegalStateException.class,
                        () -> adapter.fetch(request, pages::add, () -> false));
        assertTrue(error.getMessage().contains("does not cover all source rows"));
        assertTrue(pages.isEmpty());
        verify(port, never()).send(any());
    }

    @Test
    void incrementalRequestMustFreezeItsIndependentCalendarVersion() {
        var withoutCalendar =
                RetailSentimentDailyJobService.definition()
                        .freeze(
                                Mode.INCREMENTAL,
                                Map.of(
                                        "source_version",
                                        SOURCE_VERSION,
                                        "target_id",
                                        TARGET_ID,
                                        "bootstrap_from",
                                        FROM),
                                FROM,
                                TO,
                                TO);
        assertThrows(IllegalArgumentException.class, () -> adapter.preflight(withoutCalendar));
        verify(port, never()).bind(any(), any(), any());
    }

    @Test
    void calendarChangedAfterPlanningIsRejectedAtPreflight() {
        when(port.calendarVersion()).thenReturn("calendar-5:10:10:fixed-schema");
        var error = assertThrows(IllegalStateException.class, () -> adapter.preflight(request));
        assertTrue(
                error.getMessage()
                        .contains("calendar differs from the frozen incremental version"));
        verify(port, never()).expected(any(), any());
    }

    @Test
    void calendarChangedDuringCoveragePreventsPageDelivery() {
        when(port.calendarVersion())
                .thenReturn(
                        CALENDAR_VERSION,
                        CALENDAR_VERSION,
                        CALENDAR_VERSION,
                        "calendar-5:10:10:fixed-schema");
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDaily>>();
        var error =
                assertThrows(
                        IllegalStateException.class,
                        () -> adapter.fetch(request, pages::add, () -> false));
        assertTrue(
                error.getMessage()
                        .contains("calendar differs from the frozen incremental version"));
        verify(port).newCalendarReader();
        assertTrue(pages.isEmpty());
        verify(port, never()).send(any());
    }

    @Test
    void calendarChangedAfterActualReadbackCannotReturnSourceComplete() {
        when(port.calendarVersion())
                .thenReturn(
                        CALENDAR_VERSION,
                        CALENDAR_VERSION,
                        CALENDAR_VERSION,
                        CALENDAR_VERSION,
                        "calendar-5:10:10:fixed-schema");
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDaily>>();
        var error =
                assertThrows(
                        IllegalStateException.class,
                        () -> adapter.fetch(request, pages::add, () -> false));
        assertTrue(
                error.getMessage()
                        .contains("calendar differs from the frozen incremental version"));
        assertEquals(1, pages.size());
        verify(port).verifiedSnapshot();
    }

    @Test
    void missingTradingDayBucketIsRejectedEvenWhenAllPresentRawRowsAreCovered() {
        when(port.sourceRawRows(FROM, TO)).thenReturn(2L);
        when(port.expected(FROM, TO)).thenReturn(List.of(DAYS.getFirst()));
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDaily>>();
        var error =
                assertThrows(
                        IllegalStateException.class,
                        () -> adapter.fetch(request, pages::add, () -> false));
        assertTrue(error.getMessage().contains("complete real SSE trading calendar"));
        verify(port).newCalendarReader();
        assertTrue(pages.isEmpty());
        verify(port, never()).send(any());
    }

    @Test
    void emptySourceWithStaleOutputIsRejectedBeforePageDelivery() {
        request = explicitMaterializeRequest();
        when(port.sourceRawRows(FROM, TO)).thenReturn(0L);
        when(port.expected(FROM, TO)).thenReturn(List.of());
        when(port.actual(FROM, TO)).thenReturn(DAYS);
        var error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                adapter.fetch(
                                        request,
                                        page -> fail("Stale output cannot become verified empty"),
                                        () -> false));
        assertTrue(error.getMessage().contains("empty source has stale output"));
        verify(port).actual(FROM, TO);
        verify(port, never()).send(any());
    }

    @Test
    void genuinelyEmptyStableSourceCompletesWithoutRefresh() throws Exception {
        request = explicitMaterializeRequest();
        when(port.sourceRawRows(FROM, TO)).thenReturn(0L);
        when(port.expected(FROM, TO)).thenReturn(List.of());
        when(port.actual(FROM, TO)).thenReturn(List.of());
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDaily>>();
        var completion = adapter.fetch(request, pages::add, () -> false);
        assertEquals(1, pages.size());
        assertTrue(pages.getFirst().rows().isEmpty());
        assertEquals(0, completion.rows());
        assertTrue(completion.complete());
        verify(port, never()).send(any());
        verify(port, never()).verifiedSnapshot();
    }

    @Test
    void mvChangedAfterReadbackCannotReturnSourceComplete() {
        RetailSentimentDailySnapshot changedOutput = readySnapshot();
        when(frozen.sourceUnchanged(changedOutput)).thenReturn(true);
        when(port.snapshot()).thenReturn(frozen, frozen, changedOutput);
        var error =
                assertThrows(
                        IllegalStateException.class,
                        () -> adapter.fetch(request, page -> {}, () -> false));
        assertTrue(
                error.getMessage()
                        .contains("MV version changed after its actual readback verification"));
    }

    @Test
    void cancellationAfterPageVerificationPreventsCompletion() {
        var calls = new AtomicInteger();
        var pages = new ArrayList<SyncJobRunner.Page<RetailSentimentDaily>>();
        assertThrows(
                CancellationException.class,
                () -> adapter.fetch(request, pages::add, () -> calls.getAndIncrement() >= 2));
        assertEquals(1, pages.size());
        verify(port, never()).verifiedSnapshot();
    }

    @Test
    void nativeRefreshExclusionCoversAllDatesEvenForDifferentBoundedWindows() {
        assertEquals(
                DatasetIntervalLock.Scope.allDates("mv_retail_sentiment_daily"),
                adapter.conflictScope(request));
        var other = freeze(SOURCE_VERSION, TARGET_ID, FROM.plusDays(7), TO.plusDays(7));
        assertEquals(adapter.conflictScope(request), adapter.conflictScope(other));
    }

    @Test
    void nullableLongCodecDistinguishesNullFromZeroAndPreservesLongMaxExactly() {
        var missing =
                new RetailSentimentDaily(
                        FROM, null, null, null, null, null, null, null, null, null, null, null,
                        null);
        var zero =
                new RetailSentimentDaily(
                        FROM, null, null, null, null, null, 0L, null, null, null, null, null, null);
        var max =
                new RetailSentimentDaily(
                        FROM,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Long.MAX_VALUE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        assertEquals(116, adapter.codec().canonicalBytes(missing).length);
        assertFalse(
                Arrays.equals(
                        adapter.codec().canonicalBytes(missing),
                        adapter.codec().canonicalBytes(zero)));
        assertFalse(
                Arrays.equals(
                        adapter.codec().canonicalBytes(max), adapter.codec().canonicalBytes(zero)));
        assertFalse(adapter.codec().equivalent(missing, zero));
        assertTrue(adapter.codec().equivalent(max, max));
    }

    @Test
    void numericReadbackTolerancePreservesSignedAmountsAndExactNullableCounts() {
        var expected = DAYS.getFirst();
        var rounded =
                new RetailSentimentDaily(
                        expected.tradeDate(),
                        expected.avgRetailRatio(),
                        expected.avgRetailEntropy(),
                        expected.totalRetailAmountYi(),
                        expected.totalRetailNetInflowYi() + 1e-10,
                        expected.avgRelAggro(),
                        expected.totalQ1(),
                        expected.totalQ3(),
                        expected.avgWashTradeRatio(),
                        expected.totalSpoofCount(),
                        expected.totalManipulationCount(),
                        expected.avgMfiScore(),
                        expected.totalMainNetYi());
        assertTrue(adapter.codec().equivalent(expected, rounded));
        var wrongCount =
                new RetailSentimentDaily(
                        rounded.tradeDate(),
                        rounded.avgRetailRatio(),
                        rounded.avgRetailEntropy(),
                        rounded.totalRetailAmountYi(),
                        rounded.totalRetailNetInflowYi(),
                        rounded.avgRelAggro(),
                        rounded.totalQ1() + 1,
                        rounded.totalQ3(),
                        rounded.avgWashTradeRatio(),
                        rounded.totalSpoofCount(),
                        rounded.totalManipulationCount(),
                        rounded.avgMfiScore(),
                        rounded.totalMainNetYi());
        assertFalse(adapter.codec().equivalent(expected, wrongCount));
        var beyond =
                new RetailSentimentDaily(
                        expected.tradeDate(),
                        expected.avgRetailRatio(),
                        expected.avgRetailEntropy(),
                        expected.totalRetailAmountYi(),
                        expected.totalRetailNetInflowYi() + 1e-3,
                        expected.avgRelAggro(),
                        expected.totalQ1(),
                        expected.totalQ3(),
                        expected.avgWashTradeRatio(),
                        expected.totalSpoofCount(),
                        expected.totalManipulationCount(),
                        expected.avgMfiScore(),
                        expected.totalMainNetYi());
        assertFalse(adapter.codec().equivalent(expected, beyond));
    }

    @Test
    void reconciliationReadbackChecksActualValuesWithoutAnotherSend() throws Exception {
        when(port.readback(List.of(FROM, TO))).thenReturn(DAYS);
        when(port.walSettled()).thenReturn(true);
        assertTrue(adapter.revalidateOnly(request, () -> false).complete());
        verify(port).readback(List.of(FROM, TO));
        verify(port).walSettled();
        verify(port, never()).send(any());
    }
}
