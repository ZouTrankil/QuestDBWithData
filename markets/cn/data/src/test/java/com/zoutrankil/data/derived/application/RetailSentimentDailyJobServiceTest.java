package com.zoutrankil.data.derived.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.derived.domain.RetailSentimentDailyFullSourceScope;
import com.zoutrankil.data.derived.port.RetailSentimentDailySession;
import com.zoutrankil.data.derived.storage.RetailSentimentDailyMaterializationPort;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.derived.RetailSentimentDaily;
import com.zoutrankil.data.domain.derived.RetailSentimentDailySnapshot;
import com.zoutrankil.data.l2.application.L2DailyFeaturesJobService;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Actual SQLite durability and exclusion tests; the native database boundary is deliberately mocked. */
class RetailSentimentDailyJobServiceTest {
    @TempDir Path temporary;
    private static final LocalDate START = LocalDate.of(2026, 9, 17);
    private static final LocalDate LOGICAL = LocalDate.of(2026, 10, 6);
    private static final String TARGET = "questdb-" + "a".repeat(64);
    private static final String CALENDAR = "calendar-31:8:8:" + "b".repeat(64);
    private static final String OTHER_TARGET = "questdb-" + "c".repeat(64);
    private static final RetailSentimentDailySnapshot CURRENT = snapshot(7, 10);
    private static final List<RetailSentimentDaily> ROWS =
            List.of(
                    new RetailSentimentDaily(
                            START, 0.5, 1.2, 10.0, -2.0, 0.6, 2L, 1L, 0.2, 1L, 3L, 51.0, -1.0));

    @Test
    void verifiedEmptyExplicitMaterializationCannotAdvanceIncrementalCheckpoint() throws Exception {
        var setup = setup();
        when(setup.port.sourceRawRows(any(LocalDate.class), any(LocalDate.class))).thenReturn(0L);
        when(setup.port.expected(any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());
        when(setup.port.actual(any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());
        var emptyPlan = setup.owner.plan(START, START, LOGICAL, SyncJobDefinition.Mode.MATERIALIZE);
        var materialized = setup.owner.run(emptyPlan);
        assertEquals(SyncRunState.VERIFIED_EMPTY, materialized.result().state());
        var incremental =
                setup.owner.plan(
                        START, START.plusDays(1), LOGICAL, SyncJobDefinition.Mode.INCREMENTAL);
        assertFalse(incremental.request().parameters().containsKey("checkpoint_before"));
        assertEquals(START, incremental.request().from());
        verify(setup.port, never()).send(anyList());
        verify(setup.port, never()).newCalendarReader();
    }

    @Test
    void canonicalOwnerUsesD086AndIndependentExchangeCalendarDependencies() {
        var definition = RetailSentimentDailyJobService.definition();
        assertEquals("data.mv_retail_sentiment_daily", definition.jobId());
        assertEquals(1, definition.version());
        assertEquals("retail_sentiment_daily_owner", definition.owner());
        assertEquals("questdb.materialize", definition.ratePolicyRef());
        assertEquals("retail_sentiment_daily.range31", definition.slicePolicyRef());
        assertEquals(
                List.of(
                        new SyncJobDefinition.JobRef(
                                L2DailyFeaturesJobService.definition().jobId(),
                                L2DailyFeaturesJobService.definition().version()),
                        new SyncJobDefinition.JobRef("data.exchange_calendar", 1)),
                definition.dependencies());
    }

    @Test
    void calendarRevisionOutsideOverlapInvalidatesOldPrefixAndRechecksTheBootstrapWindow()
            throws Exception {
        var setup = setup();
        LocalDate through = START.plusDays(4), requestedTo = START.plusDays(5);
        verified(
                setup.ledger,
                "old-calendar-prefix",
                request(START, START, through, SyncJobDefinition.Mode.INCREMENTAL),
                TARGET);
        String revisedCalendar = "calendar-31:9:9:" + "d".repeat(64);
        when(setup.port.calendarVersion()).thenReturn(revisedCalendar);
        var plan =
                setup.owner.plan(START, requestedTo, LOGICAL, SyncJobDefinition.Mode.INCREMENTAL);
        assertEquals(START, plan.request().from());
        assertFalse(plan.request().parameters().containsKey("checkpoint_before"));
        assertEquals(revisedCalendar, plan.request().parameters().get("calendar_version"));
        verify(setup.port, times(1)).calendarVersion();
        // This represents a corrected open session before the old checkpoint's three-day overlap.
        when(setup.port.newCalendarReader())
                .thenReturn(
                        query ->
                                MaterializationCalendarFixture.page(
                                        query, List.of(START, START.plusDays(1))));
        var result = setup.owner.run(plan);
        assertEquals(SyncRunState.FAILED, result.result().state());
        assertEquals(0, result.result().verifiedRows());
        verify(setup.port).newCalendarReader();
        verify(setup.port, never()).send(anyList());
        assertFalse(
                setup.owner
                        .plan(START, requestedTo, LOGICAL, SyncJobDefinition.Mode.INCREMENTAL)
                        .request()
                        .parameters()
                        .containsKey("checkpoint_before"));
    }

    @Test
    void legacyIncrementalProofWithoutCalendarVersionCannotAdvanceCheckpoint() throws Exception {
        var setup = setup();
        var legacy =
                RetailSentimentDailyJobService.definition()
                        .freeze(
                                SyncJobDefinition.Mode.INCREMENTAL,
                                Map.of(
                                        "source_version",
                                        CURRENT.sourceVersion(),
                                        "target_id",
                                        TARGET,
                                        "bootstrap_from",
                                        START),
                                START,
                                START.plusDays(4),
                                LOGICAL);
        verified(setup.ledger, "legacy-unversioned-calendar", legacy, TARGET);
        var plan =
                setup.owner.plan(
                        START, START.plusDays(5), LOGICAL, SyncJobDefinition.Mode.INCREMENTAL);
        assertEquals(START, plan.request().from());
        assertFalse(plan.request().parameters().containsKey("checkpoint_before"));
        assertEquals(CALENDAR, plan.request().parameters().get("calendar_version"));
    }

    @Test
    void calendarChangeCannotTurnAnOldBootstrapIntoAnUnboundedRevalidation() throws Exception {
        var setup = setup();
        LocalDate anchor = LocalDate.of(2026, 7, 1);
        verified(
                setup.ledger,
                "old-july",
                request(
                        anchor,
                        anchor,
                        LocalDate.of(2026, 7, 31),
                        SyncJobDefinition.Mode.INCREMENTAL),
                TARGET);
        verified(
                setup.ledger,
                "old-august",
                request(
                        anchor,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31),
                        SyncJobDefinition.Mode.INCREMENTAL),
                TARGET);
        when(setup.port.calendarVersion()).thenReturn("calendar-31:9:9:" + "d".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        setup.owner.plan(
                                anchor,
                                LocalDate.of(2026, 9, 1),
                                LOGICAL,
                                SyncJobDefinition.Mode.INCREMENTAL));
        verify(setup.port, never()).send(anyList());
    }

    @Test
    void checkpointCannotCrossAnUnverifiedCalendarGap() throws Exception {
        var setup = setup();
        configureFreshPrefix(setup.port);
        var anchor = LocalDate.of(2026, 9, 1);
        verified(
                setup.ledger,
                "prefix",
                request(anchor, anchor, anchor.plusDays(4), SyncJobDefinition.Mode.INCREMENTAL),
                TARGET);
        verified(
                setup.ledger,
                "after-gap",
                request(
                        anchor,
                        anchor.plusDays(6),
                        anchor.plusDays(9),
                        SyncJobDefinition.Mode.INCREMENTAL),
                TARGET);
        var plan = setup.owner.plan(anchor, anchor.plusDays(9), LOGICAL, null);
        assertEquals(anchor.plusDays(4), plan.request().parameters().get("checkpoint_before"));
        assertEquals(anchor.plusDays(2), plan.request().from());
        assertEquals(anchor.plusDays(9), plan.request().to());
        verify(setup.port, never()).send(anyList());
    }

    @Test
    void verifiedBridgeAdvancesContiguousCoverageButOtherTargetsAndModesDoNot() throws Exception {
        var setup = setup();
        configureFreshPrefix(setup.port);
        var anchor = LocalDate.of(2026, 9, 1);
        verified(
                setup.ledger,
                "prefix",
                request(anchor, anchor, anchor.plusDays(4), SyncJobDefinition.Mode.INCREMENTAL),
                TARGET);
        verified(
                setup.ledger,
                "suffix",
                request(
                        anchor,
                        anchor.plusDays(6),
                        anchor.plusDays(9),
                        SyncJobDefinition.Mode.INCREMENTAL),
                TARGET);
        verified(
                setup.ledger,
                "bridge",
                request(
                        anchor,
                        anchor.plusDays(4),
                        anchor.plusDays(6),
                        SyncJobDefinition.Mode.INCREMENTAL),
                TARGET);
        verified(
                setup.ledger,
                "wrong-target",
                request(anchor, anchor, anchor.plusDays(19), SyncJobDefinition.Mode.INCREMENTAL),
                OTHER_TARGET);
        verified(
                setup.ledger,
                "materialize",
                request(anchor, anchor, anchor.plusDays(20), SyncJobDefinition.Mode.MATERIALIZE),
                TARGET);
        verified(
                setup.ledger,
                "other-anchor",
                request(
                        anchor.plusDays(1),
                        anchor.plusDays(1),
                        anchor.plusDays(20),
                        SyncJobDefinition.Mode.INCREMENTAL),
                TARGET);
        var plan = setup.owner.plan(anchor, anchor.plusDays(12), LOGICAL, null);
        assertEquals(anchor.plusDays(9), plan.request().parameters().get("checkpoint_before"));
        assertEquals(anchor.plusDays(7), plan.request().from());
    }

    @Test
    void oldBootstrapAnchorAllowsANewFiniteIncrementalOverlap() throws Exception {
        var setup = setup();
        configureFreshPrefix(setup.port);
        var anchor = LocalDate.of(2026, 7, 1);
        verified(
                setup.ledger,
                "july",
                request(
                        anchor,
                        anchor,
                        LocalDate.of(2026, 7, 31),
                        SyncJobDefinition.Mode.INCREMENTAL),
                TARGET);
        verified(
                setup.ledger,
                "august",
                request(
                        anchor,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31),
                        SyncJobDefinition.Mode.INCREMENTAL),
                TARGET);
        verified(
                setup.ledger,
                "september",
                request(
                        anchor,
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 2),
                        SyncJobDefinition.Mode.INCREMENTAL),
                TARGET);
        var plan = setup.owner.plan(anchor, LocalDate.of(2026, 9, 3), LOGICAL, null);
        assertEquals(anchor, plan.request().parameters().get("bootstrap_from"));
        assertEquals(
                LocalDate.of(2026, 9, 2), plan.request().parameters().get("checkpoint_before"));
        assertEquals(LocalDate.of(2026, 8, 31), plan.request().from());
        assertEquals(LocalDate.of(2026, 9, 3), plan.request().to());
    }

    @Test
    void unanchoredHistoryCannotAuthorizeAnUnboundedFirstRun() throws Exception {
        var setup = setup();
        var anchor = LocalDate.of(2026, 7, 1);
        verified(
                setup.ledger,
                "unanchored",
                request(
                        anchor,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31),
                        SyncJobDefinition.Mode.INCREMENTAL),
                TARGET);
        assertThrows(
                IllegalArgumentException.class,
                () -> setup.owner.plan(anchor, LocalDate.of(2026, 9, 3), LOGICAL, null));
        verify(setup.port, never()).send(anyList());
    }

    @Test
    void failedRunResumesItsExactFrozenWindowLogicalDateAndTarget() throws Exception {
        var setup = setup();
        var plan = setup.owner.plan(START, START, LOGICAL, SyncJobDefinition.Mode.MATERIALIZE);
        doThrow(new IllegalStateException("not admitted yet")).when(setup.port).preflight();
        var failure = setup.owner.run(plan);
        assertEquals(SyncRunState.FAILED, failure.result().state());
        assertEquals(0, failure.result().verifiedRows());
        var original = setup.ledger.getRun(failure.result().runId());
        reset(setup.port);
        configure(setup.port);
        var resumed = setup.owner.resume(original.id());
        assertEquals(SyncRunState.VERIFIED, resumed.result().state(), resumed.toString());
        var restored = setup.ledger.getRun(resumed.result().runId());
        assertEquals(original.id(), restored.parentRunId());
        assertEquals(original.logicalDate(), restored.logicalDate());
        assertEquals(original.targetId(), restored.targetId());
        assertEquals(
                SyncRequestIdentity.fingerprint(original.frozenJson(), original.targetId()),
                SyncRequestIdentity.fingerprint(restored.frozenJson(), restored.targetId()));
        verify(setup.port).send(ROWS);
    }

    @Test
    void resumeRejectsChangedSourceRatherThanReplanningAgainstTodaysVersion() throws Exception {
        var setup = setup();
        var plan = setup.owner.plan(START, START, LOGICAL, SyncJobDefinition.Mode.MATERIALIZE);
        doThrow(new IllegalStateException("disabled")).when(setup.port).preflight();
        var failed = setup.owner.run(plan);
        reset(setup.port);
        configure(setup.port);
        when(setup.port.snapshot()).thenReturn(snapshot(8, 11));
        var resumed = setup.owner.resume(failed.result().runId());
        assertEquals(SyncRunState.FAILED, resumed.result().state());
        assertEquals(0, resumed.result().verifiedRows());
        assertEquals(
                SyncRequestIdentity.fingerprint(
                        setup.ledger.getRun(failed.result().runId()).frozenJson(), TARGET),
                SyncRequestIdentity.fingerprint(
                        setup.ledger.getRun(resumed.result().runId()).frozenJson(), TARGET));
        verify(setup.port, never()).send(anyList());
    }

    @Test
    void terminalCancellationIsRejectedWithoutChangingVerificationOrAddingACancellation()
            throws Exception {
        var setup = setup();
        verified(
                setup.ledger,
                "completed",
                request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE),
                TARGET);
        long revision = setup.ledger.get("completed").revision();
        assertFalse(setup.owner.cancel("completed"));
        assertFalse(setup.ledger.cancellationRequested("completed"));
        assertEquals(SyncRunState.VERIFIED, setup.ledger.get("completed").state());
        assertEquals(revision, setup.ledger.get("completed").revision());
        verifyNoInteractions(setup.port);
    }

    @Test
    void activeCancellationIsDurableAndDoesNotFalselyCompleteTheRun() throws Exception {
        var setup = setup();
        setup.ledger.createRun(
                "active",
                null,
                TARGET,
                request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE));
        setup.ledger.transition("active", 0, SyncRunState.RUNNING, "{}");
        assertTrue(setup.owner.cancel("active"));
        assertTrue(SyncRunLedger.openReadOnly(setup.path).cancellationRequested("active"));
        assertEquals(SyncRunState.RUNNING, setup.ledger.get("active").state());
        assertTrue(setup.owner.cancel("active"));
        assertEquals(1, setup.ledger.get("active").revision());
    }

    @Test
    void uncertainRunRetainsIntervalExclusionAndCannotBeResumedOrReplayed() throws Exception {
        var setup = setup();
        var request = request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE);
        var uncertain = uncertain(setup, "uncertain", request);
        assertThrows(IllegalStateException.class, () -> setup.owner.resume("uncertain"));
        var refused =
                setup.owner.run(new RetailSentimentDailyJobService.Plan(request, TARGET, CURRENT));
        assertEquals(SyncRunState.FAILED, refused.result().state());
        assertEquals("DATASET_INTERVAL_BUSY", refused.result().errorCode());
        var retained = uncertain.locks.findOwned("uncertain", uncertain.lease.scope());
        assertNotNull(retained);
        assertTrue(retained.inDoubt());
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get("uncertain").state());
        verify(setup.port, never()).send(anyList());
    }

    @Test
    void uncertainNativeRefreshBlocksANonOverlappingWindowWithoutSending() throws Exception {
        var setup = setup();
        var uncertain =
                uncertain(
                        setup,
                        "uncertain",
                        request(START, START, START, SyncJobDefinition.Mode.INCREMENTAL));
        LocalDate otherDate = START.plusDays(10);
        var disjoint = request(otherDate, otherDate, otherDate, SyncJobDefinition.Mode.INCREMENTAL);
        var refused =
                setup.owner.run(new RetailSentimentDailyJobService.Plan(disjoint, TARGET, CURRENT));
        assertEquals(SyncRunState.FAILED, refused.result().state());
        assertEquals("DATASET_INTERVAL_BUSY", refused.result().errorCode());
        assertEquals(
                DatasetIntervalLock.Scope.allDates("mv_retail_sentiment_daily"),
                uncertain.lease.scope());
        assertTrue(uncertain.locks.findOwned("uncertain", uncertain.lease.scope()).inDoubt());
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get("uncertain").state());
        verify(setup.port, never()).expected(any(LocalDate.class), any(LocalDate.class));
        verify(setup.port, never()).send(anyList());
    }

    @Test
    void stoppedWriterReconciliationVerifiesActualValuesAndReleasesTheRetainedLeaseWithoutWriting()
            throws Exception {
        var setup = setup();
        var uncertain =
                uncertain(
                        setup,
                        "uncertain",
                        request(START, START, START, SyncJobDefinition.Mode.INCREMENTAL));
        assertThrows(
                IllegalArgumentException.class, () -> setup.owner.reconcile("uncertain", false));
        var status = setup.owner.reconcile("uncertain", true);
        assertEquals(SyncRunState.VERIFIED, status.state());
        assertEquals(1, status.verifiedRows());
        assertEquals(0, status.unresolvedSlices());
        assertNull(uncertain.locks.findOwned("uncertain", uncertain.lease.scope()));
        assertEquals(SyncRunState.VERIFIED, setup.ledger.get(uncertain.slice).state());
        assertEquals(SyncRunState.VERIFIED, setup.ledger.get(uncertain.attempt).state());
        var proof =
                JobDefinitionJson.mapper()
                        .readTree(setup.ledger.get("uncertain").payloadJson())
                        .path("verification");
        assertTrue(proof.path("writerStopped").asBoolean());
        assertEquals(
                "d098-reconcile:" + CURRENT.stableVersion(),
                proof.path("readbackEvidence").asText());
        verify(setup.port).readback(List.of(START));
        verify(setup.port, never()).preflight();
        verify(setup.port, never()).send(anyList());
        verify(setup.port, never()).createIsolatedTarget();
        verify(setup.port, never()).configureFullIsolated();
    }

    @Test
    void alreadyVerifiedRunWithRetainedLeaseCanFinishAfterCrashBeforeLockRelease()
            throws Exception {
        var setup = setup();
        var uncertain =
                uncertain(
                        setup,
                        "uncertain",
                        request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE));
        completeUncertain(setup.ledger, uncertain);
        long ownerRevision = setup.ledger.get("uncertain").revision();
        assertNotNull(uncertain.locks.findOwned("uncertain", uncertain.lease.scope()));
        var result = setup.owner.reconcile("uncertain", true);
        assertEquals(SyncRunState.VERIFIED, result.state());
        assertEquals(ownerRevision, setup.ledger.get("uncertain").revision());
        assertNull(uncertain.locks.findOwned("uncertain", uncertain.lease.scope()));
        verify(setup.port, never()).send(anyList());
        verify(setup.port, never()).preflight();
    }

    @Test
    void legacyWindowLeaseRequiresRealReadbackBeforeItCanBeReleased() throws Exception {
        var setup = setup();
        var frozen = request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE);
        var legacyScope = new DatasetIntervalLock.Scope("mv_retail_sentiment_daily", START, START);
        var uncertain = uncertain(setup, "legacy", frozen, legacyScope);
        assertEquals(SyncRunState.VERIFIED, setup.owner.reconcile("legacy", true).state());
        assertNull(uncertain.locks.findOwned("legacy", legacyScope));
        verify(setup.port).readback(List.of(START));
        verify(setup.port, never()).send(anyList());
    }

    @Test
    void legacyWindowLeaseRemainsRetainedWhenRealReadbackVersionChanges() throws Exception {
        var setup = setup();
        var frozen = request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE);
        var legacyScope = new DatasetIntervalLock.Scope("mv_retail_sentiment_daily", START, START);
        var uncertain = uncertain(setup, "legacy", frozen, legacyScope);
        when(setup.port.snapshot()).thenReturn(CURRENT, CURRENT, CURRENT, CURRENT, snapshot(7, 11));
        assertThrows(IllegalStateException.class, () -> setup.owner.reconcile("legacy", true));
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get("legacy").state());
        assertTrue(uncertain.locks.findOwned("legacy", legacyScope).inDoubt());
        verify(setup.port).readback(List.of(START));
        verify(setup.port, never()).send(anyList());
    }

    @Test
    void sourceDriftAfterReadbackCannotAdvanceTheRunOrReleaseItsLease() throws Exception {
        assertPostReadbackDrift(snapshot(8, 10));
    }

    @Test
    void materializedOutputDriftAfterReadbackCannotAdvanceTheRunOrReleaseItsLease()
            throws Exception {
        assertPostReadbackDrift(snapshot(7, 11));
    }

    @Test
    void calendarDriftAfterReadbackCannotAdvanceTheIncrementalCheckpoint() throws Exception {
        var setup = setup();
        var uncertain =
                uncertain(
                        setup,
                        "uncertain",
                        request(START, START, START, SyncJobDefinition.Mode.INCREMENTAL));
        when(setup.port.calendarVersion())
                .thenReturn(CALENDAR, CALENDAR, CALENDAR, CALENDAR, CALENDAR, "calendar-changed");
        assertThrows(IllegalStateException.class, () -> setup.owner.reconcile("uncertain", true));
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get("uncertain").state());
        assertTrue(uncertain.locks.findOwned("uncertain", uncertain.lease.scope()).inDoubt());
        verify(setup.port, never()).send(anyList());
    }

    @Test
    void aDisabledOrUnadmittedFormalPortRejectsBeforeQueryingOrSending() {
        var jdbc = mock(JdbcTemplate.class);
        var properties = new QuestDbProperties();
        var disabled = new RetailSentimentDailyMaterializationPort(jdbc, properties, false);
        disabled.bind(START, START, CURRENT);
        assertThrows(IllegalStateException.class, disabled::preflight);
        var unadmitted = new RetailSentimentDailyMaterializationPort(jdbc, properties, true);
        unadmitted.bind(START, START, CURRENT);
        assertThrows(IllegalStateException.class, unadmitted::preflight);
        verifyNoInteractions(jdbc);
    }

    @Test
    void isolatedFullRepairRunsThroughCanonicalFrozenMaterializationAndLedger() throws Exception {
        var setup = setup();
        RetailSentimentDailySnapshot invalid =
                new RetailSentimentDailySnapshot(
                        CURRENT.sourceId(),
                        CURRENT.sourceDirectory(),
                        CURRENT.sourceTableTxn(),
                        CURRENT.sourceSeqTxn(),
                        CURRENT.sourceWriterTxn(),
                        true,
                        CURRENT.mvId(),
                        CURRENT.mvDirectory(),
                        CURRENT.mvTxn(),
                        CURRENT.mvSeqTxn(),
                        CURRENT.mvWriterTxn(),
                        true,
                        false,
                        false,
                        CURRENT.definitionSha(),
                        CURRENT.refreshStarted(),
                        CURRENT.refreshFinished(),
                        CURRENT.refreshBaseTxn(),
                        CURRENT.reportedBaseTxn(),
                        CURRENT.sourcePartition(),
                        "invalid");
        var observed = new AtomicReference<>(invalid);
        when(setup.port.snapshot()).thenAnswer(call -> observed.get());
        doAnswer(
                        call -> {
                            observed.set(CURRENT);
                            return null;
                        })
                .when(setup.port)
                .send(ROWS);
        var repaired = setup.owner.repairIsolated();
        assertEquals(SyncRunState.VERIFIED, repaired.result().state());
        assertEquals(2, repaired.sourceRawRows());
        assertEquals(CURRENT, repaired.target());
        assertNull(repaired.targetSnapshotError());
        var frozen =
                JobDefinitionJson.mapper()
                        .readTree(setup.ledger.getRun(repaired.result().runId()).frozenJson());
        assertEquals("MATERIALIZE", frozen.path("mode").asText());
        assertEquals("FULL_ISOLATED", frozen.path("parameters").path("native_refresh").asText());
        assertEquals(START.toString(), frozen.path("from").asText());
        assertEquals(START.toString(), frozen.path("to").asText());
        assertEquals(
                1,
                setup.ledger.entries(repaired.result().runId(), null, 100).stream()
                        .filter(entry -> entry.kind() == SyncRunLedger.Kind.SLICE)
                        .count());
        verify(setup.port).configureFullIsolated();
        verify(setup.port).send(ROWS);
    }

    @Test
    void acknowledgedFullRefreshVisibilityTimeoutRetainsLeaseAndCannotBeRepeated()
            throws Exception {
        var setup = setup();
        when(setup.port.walSettled()).thenReturn(false);
        when(setup.port.unresolved()).thenReturn(true);
        var uncertain = setup.owner.repairIsolated();
        assertFullUncertaintyBlocksReplay(setup, uncertain);
    }

    @Test
    void lostFullRefreshAcknowledgementRetainsDurableSubmittedIntentAndCannotBeRepeated()
            throws Exception {
        var setup = setup();
        doThrow(new IllegalStateException("simulated lost native refresh acknowledgement"))
                .when(setup.port)
                .send(ROWS);
        when(setup.port.uncertainSenderStopped()).thenReturn(false);
        when(setup.port.unresolved()).thenReturn(true);
        var uncertain = setup.owner.repairIsolated();
        assertFullUncertaintyBlocksReplay(setup, uncertain);
        verify(setup.port, never()).readback(anyList());
    }

    @Test
    void defaultDisabledFullRepairCannotRunAFormalStatement() {
        var jdbc = mock(JdbcTemplate.class);
        var disabled =
                new RetailSentimentDailyMaterializationPort(jdbc, new QuestDbProperties(), false);
        assertThrows(IllegalStateException.class, disabled::configureFullIsolated);
        assertThrows(IllegalStateException.class, disabled::fullSourceScope);
        var admittedFormal =
                new RetailSentimentDailyMaterializationPort(
                        jdbc, new QuestDbProperties(), true, TARGET);
        assertThrows(IllegalStateException.class, admittedFormal::configureFullIsolated);
        verifyNoInteractions(jdbc);
    }

    @Test
    void fullRepairRejectsEmptyAndUnboundedWholeSourceScopes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RetailSentimentDailyFullSourceScope(START, START, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RetailSentimentDailyFullSourceScope(START, START, 200_001));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RetailSentimentDailyFullSourceScope(START, START.plusDays(31), 2));
    }

    @Test
    void durableRunResultRemainsVisibleWhenItsDecorativeSnapshotCannotBeRead() throws Exception {
        var setup = setup();
        doThrow(new IllegalStateException("not admitted")).when(setup.port).preflight();
        // Planning has already fixed its source, so failure here is only the post-run decoration.
        when(setup.port.snapshot())
                .thenThrow(new IllegalStateException("private metadata unavailable"));
        var saved = request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE);
        var failed =
                setup.owner.run(new RetailSentimentDailyJobService.Plan(saved, TARGET, CURRENT));
        assertEquals(SyncRunState.FAILED, failed.result().state());
        assertNotNull(failed.result().runId());
        assertNull(failed.target());
        assertEquals("IllegalStateException", failed.targetSnapshotError());
        assertEquals(SyncRunState.FAILED, setup.ledger.get(failed.result().runId()).state());
        var status = setup.owner.status(failed.result().runId());
        assertEquals(SyncRunState.FAILED, status.state());
        assertNull(status.currentTarget());
        assertEquals("IllegalStateException", status.currentTargetError());
    }

    private static void assertFullUncertaintyBlocksReplay(
            Setup setup, RetailSentimentDailyJobService.MaterializationResult uncertain)
            throws Exception {
        assertEquals(SyncRunState.IN_DOUBT, uncertain.result().state());
        String id = uncertain.result().runId();
        var lock = new DatasetIntervalLock(setup.path);
        var scope = DatasetIntervalLock.Scope.allDates("mv_retail_sentiment_daily");
        var lease = lock.findOwned(id, scope);
        assertNotNull(lease);
        assertTrue(lease.inDoubt());
        var slice =
                setup.ledger.entries(id, null, 100).stream()
                        .filter(entry -> entry.kind() == SyncRunLedger.Kind.SLICE)
                        .findFirst()
                        .orElseThrow();
        assertEquals(SyncRunState.IN_DOUBT, slice.state());
        assertTrue(
                setup.ledger.events(slice.id(), -1, 20).stream()
                        .anyMatch(event -> event.state() == SyncRunState.SUBMITTED));
        var frozen = JobDefinitionJson.mapper().readTree(setup.ledger.getRun(id).frozenJson());
        assertEquals("FULL_ISOLATED", frozen.path("parameters").path("native_refresh").asText());
        assertThrows(IllegalStateException.class, () -> setup.owner.resume(id));
        var replay = setup.owner.repairIsolated();
        assertEquals(SyncRunState.FAILED, replay.result().state());
        assertEquals("DATASET_INTERVAL_BUSY", replay.result().errorCode());
        assertTrue(lock.findOwned(id, scope).inDoubt());
        verify(setup.port, times(1)).send(ROWS);
    }

    private void assertPostReadbackDrift(RetailSentimentDailySnapshot changed) throws Exception {
        var setup = setup();
        var uncertain =
                uncertain(
                        setup,
                        "uncertain",
                        request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE));
        when(setup.port.snapshot()).thenReturn(CURRENT, CURRENT, CURRENT, CURRENT, changed);
        assertThrows(IllegalStateException.class, () -> setup.owner.reconcile("uncertain", true));
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get("uncertain").state());
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get(uncertain.slice).state());
        assertTrue(uncertain.locks.findOwned("uncertain", uncertain.lease.scope()).inDoubt());
        verify(setup.port, never()).send(anyList());
    }

    private record Setup(
            Path path,
            SyncRunLedger ledger,
            RetailSentimentDailySession port,
            RetailSentimentDailyJobService owner) {}

    private Setup setup() throws Exception {
        Path path = temporary.resolve("ledger-" + UUID.randomUUID() + ".sqlite3");
        var ledger = new SyncRunLedger(path);
        var port = mock(RetailSentimentDailySession.class);
        configure(port);
        return new Setup(path, ledger, port, new RetailSentimentDailyJobService(() -> port, path));
    }

    private static void configure(RetailSentimentDailySession port) {
        when(port.snapshot()).thenReturn(CURRENT);
        when(port.targetId()).thenReturn(TARGET);
        when(port.calendarVersion()).thenReturn(CALENDAR);
        when(port.newCalendarReader())
                .thenReturn(query -> MaterializationCalendarFixture.page(query, List.of(START)));
        when(port.sourceRawRows(any(LocalDate.class), any(LocalDate.class))).thenReturn(2L);
        when(port.expected(any(LocalDate.class), any(LocalDate.class))).thenReturn(ROWS);
        when(port.actual(any(LocalDate.class), any(LocalDate.class))).thenReturn(ROWS);
        when(port.readback(anyList())).thenReturn(ROWS);
        when(port.walSettled()).thenReturn(true);
        when(port.verifiedSnapshot()).thenReturn(CURRENT);
        when(port.unresolved()).thenReturn(false);
        when(port.visibilityTimeout()).thenReturn(Duration.ofMillis(20));
        when(port.fullSourceScope())
                .thenReturn(new RetailSentimentDailyFullSourceScope(START, START, 2));
        when(port.outputRowCount()).thenReturn(1L);
    }

    // 区间拼接测试的当前来源由完整有限日历与实际窗口行组成，不靠成功状态跳过回读。
    private static void configureFreshPrefix(RetailSentimentDailySession port) {
        when(port.newCalendarReader())
                .thenReturn(
                        query -> {
                            LocalDate from = (LocalDate) query.fromInclusive(),
                                    to = (LocalDate) query.toExclusive();
                            return MaterializationCalendarFixture.page(
                                    query, from.datesUntil(to).toList());
                        });
        when(port.expected(any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(call -> prefixRows(call.getArgument(0), call.getArgument(1)));
        when(port.sourceRawRows(any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(
                        call -> 2L * prefixRows(call.getArgument(0), call.getArgument(1)).size());
        when(port.readback(anyList()))
                .thenAnswer(
                        call -> {
                            List<LocalDate> dates = call.getArgument(0);
                            return dates.stream()
                                    .map(RetailSentimentDailyJobServiceTest::prefixRow)
                                    .toList();
                        });
    }

    private static List<RetailSentimentDaily> prefixRows(LocalDate from, LocalDate to) {
        return from.datesUntil(to.plusDays(1))
                .map(RetailSentimentDailyJobServiceTest::prefixRow)
                .toList();
    }

    private static RetailSentimentDaily prefixRow(LocalDate day) {
        var r = ROWS.getFirst();
        return new RetailSentimentDaily(
                day,
                r.avgRetailRatio(),
                r.avgRetailEntropy(),
                r.totalRetailAmountYi(),
                r.totalRetailNetInflowYi(),
                r.avgRelAggro(),
                r.totalQ1(),
                r.totalQ3(),
                r.avgWashTradeRatio(),
                r.totalSpoofCount(),
                r.totalManipulationCount(),
                r.avgMfiScore(),
                r.totalMainNetYi());
    }

    private static SyncJobDefinition.FrozenRequest request(
            LocalDate anchor, LocalDate from, LocalDate to, SyncJobDefinition.Mode mode) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("source_version", CURRENT.sourceVersion());
        parameters.put("target_id", TARGET);
        parameters.put("bootstrap_from", anchor);
        if (mode == SyncJobDefinition.Mode.INCREMENTAL)
            parameters.put("calendar_version", CALENDAR);
        return RetailSentimentDailyJobService.definition()
                .freeze(mode, parameters, from, to, LOGICAL);
    }

    private static void verified(
            SyncRunLedger ledger,
            String runId,
            SyncJobDefinition.FrozenRequest request,
            String targetId)
            throws Exception {
        ledger.createRun(runId, null, targetId, request);
        ledger.transition(runId, 0, SyncRunState.RUNNING, "{}");
        ledger.transition(runId, 1, SyncRunState.VERIFIED, proof(false));
    }

    private record Uncertain(
            String runId,
            String attempt,
            String slice,
            DatasetIntervalLock locks,
            DatasetIntervalLock.Lease lease) {}

    private static Uncertain uncertain(
            Setup setup, String id, SyncJobDefinition.FrozenRequest request) throws Exception {
        return uncertain(
                setup,
                id,
                request,
                DatasetIntervalLock.Scope.allDates("mv_retail_sentiment_daily"));
    }

    private static Uncertain uncertain(
            Setup setup,
            String id,
            SyncJobDefinition.FrozenRequest request,
            DatasetIntervalLock.Scope scope)
            throws Exception {
        var ledger = setup.ledger;
        ledger.createRun(id, null, TARGET, request);
        ledger.transition(id, 0, SyncRunState.RUNNING, "{}");
        String attempt = id + "-attempt", slice = id + "-slice";
        ledger.createChild(attempt, SyncRunLedger.Kind.ATTEMPT, id, id);
        ledger.transition(attempt, 0, SyncRunState.RUNNING, "{}");
        ledger.createChild(slice, SyncRunLedger.Kind.SLICE, id, attempt);
        ledger.transition(slice, 0, SyncRunState.RUNNING, "{}");
        ledger.transition(slice, 1, SyncRunState.FETCHED, "{\"returnedRows\":1}");
        ledger.transition(slice, 2, SyncRunState.VALIDATED, "{}");
        ledger.transition(slice, 3, SyncRunState.SUBMITTED, "{}");
        var locks = new DatasetIntervalLock(setup.path);
        var lease = locks.acquire(id, scope);
        assertNotNull(lease);
        locks.retainInDoubt(lease);
        ledger.transition(slice, 4, SyncRunState.IN_DOUBT, "{\"errorCode\":\"LOST_REFRESH_ACK\"}");
        ledger.transition(attempt, 1, SyncRunState.IN_DOUBT, "{}");
        ledger.transition(id, 1, SyncRunState.IN_DOUBT, "{}");
        return new Uncertain(id, attempt, slice, locks, locks.findOwned(id, lease.scope()));
    }

    private static void completeUncertain(SyncRunLedger ledger, Uncertain uncertain)
            throws Exception {
        ledger.transition(
                uncertain.slice,
                ledger.get(uncertain.slice).revision(),
                SyncRunState.VERIFIED,
                proof(true));
        ledger.transition(
                uncertain.attempt,
                ledger.get(uncertain.attempt).revision(),
                SyncRunState.VERIFIED,
                proof(true));
        ledger.transition(
                uncertain.runId,
                ledger.get(uncertain.runId).revision(),
                SyncRunState.VERIFIED,
                proof(true));
    }

    private static String proof(boolean stopped) throws Exception {
        return JobDefinitionJson.mapper()
                .writeValueAsString(
                        Map.of(
                                "verification",
                                Map.of(
                                        "passed",
                                        true,
                                        "expectedRows",
                                        1,
                                        "actualRows",
                                        1,
                                        "matchedRows",
                                        1,
                                        "mismatchedRows",
                                        0,
                                        "duplicateKeys",
                                        0,
                                        "missingKeys",
                                        0,
                                        "sourceFingerprint",
                                        "unit-source",
                                        "readbackEvidence",
                                        "unit-native-readback",
                                        "writerStopped",
                                        stopped)));
    }

    private static RetailSentimentDailySnapshot snapshot(long sourceTxn, long outputTxn) {
        return new RetailSentimentDailySnapshot(
                11,
                "l2_daily_features~11",
                sourceTxn,
                sourceTxn,
                sourceTxn,
                true,
                12,
                "mv_retail_sentiment_daily~12",
                outputTxn,
                outputTxn,
                outputTxn,
                true,
                true,
                true,
                RetailSentimentDailyMaterializationPort.DEFINITION_SHA,
                "2026-10-06T00:00:00Z",
                "2026-10-06T00:00:01Z",
                sourceTxn,
                sourceTxn,
                "DAY",
                "valid");
    }
}
