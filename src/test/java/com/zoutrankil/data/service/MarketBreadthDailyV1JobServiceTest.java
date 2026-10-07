package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.MarketBreadthDailyV1;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.MarketBreadthDailyV1MaterializationPort;
import com.zoutrankil.data.repository.MarketBreadthDailyV1MaterializationPort.Snapshot;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/** Actual SQLite durability and exclusion tests; the native database boundary is deliberately mocked. */
class MarketBreadthDailyV1JobServiceTest {
    @TempDir Path temporary;
    private static final LocalDate START = LocalDate.of(2026, 9, 17);
    private static final LocalDate LOGICAL = LocalDate.of(2026, 10, 6);
    private static final String TARGET = "questdb-" + "a".repeat(64);
    private static final String CALENDAR = "calendar-31:8:8:" + "b".repeat(64);
    private static final String OTHER_TARGET = "questdb-" + "c".repeat(64);
    private static final Snapshot CURRENT = snapshot(7, 10);
    private static final List<MarketBreadthDailyV1> ROWS = List.of(
            new MarketBreadthDailyV1(START, 2, 1, 1, 0, 0.0, 10.0));

    @Test void checkpointCannotCrossAnUnverifiedCalendarGap() throws Exception {
        var setup = setup();
        var anchor = LocalDate.of(2026, 9, 1);
        verified(setup.ledger, "prefix", request(anchor, anchor, anchor.plusDays(4), SyncJobDefinition.Mode.INCREMENTAL), TARGET);
        verified(setup.ledger, "after-gap", request(anchor, anchor.plusDays(6), anchor.plusDays(9), SyncJobDefinition.Mode.INCREMENTAL), TARGET);
        var plan = setup.owner.plan(anchor, anchor.plusDays(9), LOGICAL, null);
        assertEquals(anchor.plusDays(4), plan.request().parameters().get("checkpoint_before"));
        assertEquals(anchor.plusDays(2), plan.request().from());
        assertEquals(anchor.plusDays(9), plan.request().to());
        verify(setup.port, never()).send(anyList());
    }

    @Test void verifiedBridgeAdvancesContiguousCoverageButOtherTargetsAndModesDoNot() throws Exception {
        var setup = setup();
        var anchor = LocalDate.of(2026, 9, 1);
        verified(setup.ledger, "prefix", request(anchor, anchor, anchor.plusDays(4), SyncJobDefinition.Mode.INCREMENTAL), TARGET);
        verified(setup.ledger, "suffix", request(anchor, anchor.plusDays(6), anchor.plusDays(9), SyncJobDefinition.Mode.INCREMENTAL), TARGET);
        verified(setup.ledger, "bridge", request(anchor, anchor.plusDays(4), anchor.plusDays(6), SyncJobDefinition.Mode.INCREMENTAL), TARGET);
        verified(setup.ledger, "wrong-target", request(anchor, anchor, anchor.plusDays(19), SyncJobDefinition.Mode.INCREMENTAL), OTHER_TARGET);
        verified(setup.ledger, "materialize", request(anchor, anchor, anchor.plusDays(20), SyncJobDefinition.Mode.MATERIALIZE), TARGET);
        verified(setup.ledger, "other-anchor", request(anchor.plusDays(1), anchor.plusDays(1), anchor.plusDays(20), SyncJobDefinition.Mode.INCREMENTAL), TARGET);
        var plan = setup.owner.plan(anchor, anchor.plusDays(12), LOGICAL, null);
        assertEquals(anchor.plusDays(9), plan.request().parameters().get("checkpoint_before"));
        assertEquals(anchor.plusDays(7), plan.request().from());
    }

    @Test void oldBootstrapAnchorAllowsANewFiniteIncrementalOverlap() throws Exception {
        var setup = setup();
        var anchor = LocalDate.of(2026, 7, 1);
        verified(setup.ledger, "july", request(anchor, anchor, LocalDate.of(2026, 7, 31), SyncJobDefinition.Mode.INCREMENTAL), TARGET);
        verified(setup.ledger, "august", request(anchor, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), SyncJobDefinition.Mode.INCREMENTAL), TARGET);
        verified(setup.ledger, "september", request(anchor, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), SyncJobDefinition.Mode.INCREMENTAL), TARGET);
        var plan = setup.owner.plan(anchor, LocalDate.of(2026, 9, 3), LOGICAL, null);
        assertEquals(anchor, plan.request().parameters().get("bootstrap_from"));
        assertEquals(LocalDate.of(2026, 9, 2), plan.request().parameters().get("checkpoint_before"));
        assertEquals(LocalDate.of(2026, 8, 31), plan.request().from());
        assertEquals(LocalDate.of(2026, 9, 3), plan.request().to());
    }

    @Test void unanchoredHistoryCannotAuthorizeAnUnboundedFirstRun() throws Exception {
        var setup = setup();
        var anchor = LocalDate.of(2026, 7, 1);
        verified(setup.ledger, "unanchored", request(anchor, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), SyncJobDefinition.Mode.INCREMENTAL), TARGET);
        assertThrows(IllegalArgumentException.class,
                () -> setup.owner.plan(anchor, LocalDate.of(2026, 9, 3), LOGICAL, null));
        verify(setup.port, never()).send(anyList());
    }

    @Test void failedRunResumesItsExactFrozenWindowLogicalDateAndTarget() throws Exception {
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
        assertEquals(SyncRequestIdentity.fingerprint(original.frozenJson(), original.targetId()),
                SyncRequestIdentity.fingerprint(restored.frozenJson(), restored.targetId()));
        verify(setup.port).send(ROWS);
    }

    @Test void resumeRejectsChangedSourceRatherThanReplanningAgainstTodaysVersion() throws Exception {
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
        assertEquals(SyncRequestIdentity.fingerprint(setup.ledger.getRun(failed.result().runId()).frozenJson(), TARGET),
                SyncRequestIdentity.fingerprint(setup.ledger.getRun(resumed.result().runId()).frozenJson(), TARGET));
        verify(setup.port, never()).send(anyList());
    }

    @Test void terminalCancellationIsRejectedWithoutChangingVerificationOrAddingACancellation() throws Exception {
        var setup = setup();
        verified(setup.ledger, "completed", request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE), TARGET);
        long revision = setup.ledger.get("completed").revision();
        assertFalse(setup.owner.cancel("completed"));
        assertFalse(setup.ledger.cancellationRequested("completed"));
        assertEquals(SyncRunState.VERIFIED, setup.ledger.get("completed").state());
        assertEquals(revision, setup.ledger.get("completed").revision());
        verifyNoInteractions(setup.port);
    }

    @Test void activeCancellationIsDurableAndDoesNotFalselyCompleteTheRun() throws Exception {
        var setup = setup();
        setup.ledger.createRun("active", null, TARGET, request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE));
        setup.ledger.transition("active", 0, SyncRunState.RUNNING, "{}");
        assertTrue(setup.owner.cancel("active"));
        assertTrue(SyncRunLedger.openReadOnly(setup.path).cancellationRequested("active"));
        assertEquals(SyncRunState.RUNNING, setup.ledger.get("active").state());
        assertTrue(setup.owner.cancel("active"));
        assertEquals(1, setup.ledger.get("active").revision());
    }

    @Test void uncertainRunRetainsIntervalExclusionAndCannotBeResumedOrReplayed() throws Exception {
        var setup = setup();
        var request = request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE);
        var uncertain = uncertain(setup, "uncertain", request);
        assertThrows(IllegalStateException.class, () -> setup.owner.resume("uncertain"));
        var refused = setup.owner.run(new MarketBreadthDailyV1JobService.Plan(request, TARGET, CURRENT));
        assertEquals(SyncRunState.FAILED, refused.result().state());
        assertEquals("DATASET_INTERVAL_BUSY", refused.result().errorCode());
        var retained = uncertain.locks.findOwned("uncertain", uncertain.lease.scope());
        assertNotNull(retained);
        assertTrue(retained.inDoubt());
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get("uncertain").state());
        verify(setup.port, never()).send(anyList());
    }

    @Test void uncertainNativeRefreshBlocksANonOverlappingWindowWithoutSending() throws Exception {
        var setup = setup();
        var uncertain = uncertain(setup, "uncertain", request(START, START, START, SyncJobDefinition.Mode.INCREMENTAL));
        LocalDate otherDate = START.plusDays(10);
        var disjoint = request(otherDate, otherDate, otherDate, SyncJobDefinition.Mode.INCREMENTAL);
        var refused = setup.owner.run(new MarketBreadthDailyV1JobService.Plan(disjoint, TARGET, CURRENT));
        assertEquals(SyncRunState.FAILED, refused.result().state());
        assertEquals("DATASET_INTERVAL_BUSY", refused.result().errorCode());
        assertEquals(DatasetIntervalLock.Scope.allDates("mv_market_breadth_daily_v1"), uncertain.lease.scope());
        assertTrue(uncertain.locks.findOwned("uncertain", uncertain.lease.scope()).inDoubt());
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get("uncertain").state());
        verify(setup.port, never()).expected(any(LocalDate.class), any(LocalDate.class));
        verify(setup.port, never()).send(anyList());
    }

    @Test void stoppedWriterReconciliationVerifiesActualValuesAndReleasesTheRetainedLeaseWithoutWriting() throws Exception {
        var setup = setup();
        var uncertain = uncertain(setup, "uncertain", request(START, START, START, SyncJobDefinition.Mode.INCREMENTAL));
        assertThrows(IllegalArgumentException.class, () -> setup.owner.reconcile("uncertain", false));
        var status = setup.owner.reconcile("uncertain", true);
        assertEquals(SyncRunState.VERIFIED, status.state());
        assertEquals(1, status.verifiedRows());
        assertEquals(0, status.unresolvedSlices());
        assertNull(uncertain.locks.findOwned("uncertain", uncertain.lease.scope()));
        assertEquals(SyncRunState.VERIFIED, setup.ledger.get(uncertain.slice).state());
        assertEquals(SyncRunState.VERIFIED, setup.ledger.get(uncertain.attempt).state());
        var proof = com.zoutrankil.data.domain.JobDefinitionJson.mapper()
                .readTree(setup.ledger.get("uncertain").payloadJson()).path("verification");
        assertTrue(proof.path("writerStopped").asBoolean());
        assertEquals("d095-reconcile:" + CURRENT.stableVersion(), proof.path("readbackEvidence").asText());
        verify(setup.port).readback(List.of(START));
        verify(setup.port, never()).preflight();
        verify(setup.port, never()).send(anyList());
        verify(setup.port, never()).createIsolatedTarget();
        verify(setup.port, never()).configureFullIsolated();
    }

    @Test void alreadyVerifiedRunWithRetainedLeaseCanFinishAfterCrashBeforeLockRelease() throws Exception {
        var setup = setup();
        var uncertain = uncertain(setup, "uncertain", request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE));
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

    @Test void legacyWindowLeaseRequiresRealReadbackBeforeItCanBeReleased() throws Exception {
        var setup = setup();
        var frozen = request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE);
        var legacyScope = new DatasetIntervalLock.Scope("mv_market_breadth_daily_v1", START, START);
        var uncertain = uncertain(setup, "legacy", frozen, legacyScope);
        assertEquals(SyncRunState.VERIFIED, setup.owner.reconcile("legacy", true).state());
        assertNull(uncertain.locks.findOwned("legacy", legacyScope));
        verify(setup.port).readback(List.of(START));
        verify(setup.port, never()).send(anyList());
    }

    @Test void legacyWindowLeaseRemainsRetainedWhenRealReadbackVersionChanges() throws Exception {
        var setup = setup();
        var frozen = request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE);
        var legacyScope = new DatasetIntervalLock.Scope("mv_market_breadth_daily_v1", START, START);
        var uncertain = uncertain(setup, "legacy", frozen, legacyScope);
        when(setup.port.snapshot()).thenReturn(CURRENT, CURRENT, CURRENT, CURRENT, snapshot(7, 11));
        assertThrows(IllegalStateException.class, () -> setup.owner.reconcile("legacy", true));
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get("legacy").state());
        assertTrue(uncertain.locks.findOwned("legacy", legacyScope).inDoubt());
        verify(setup.port).readback(List.of(START));
        verify(setup.port, never()).send(anyList());
    }

    @Test void sourceDriftAfterReadbackCannotAdvanceTheRunOrReleaseItsLease() throws Exception {
        assertPostReadbackDrift(snapshot(8, 10));
    }

    @Test void materializedOutputDriftAfterReadbackCannotAdvanceTheRunOrReleaseItsLease() throws Exception {
        assertPostReadbackDrift(snapshot(7, 11));
    }

    @Test void calendarDriftAfterReadbackCannotAdvanceTheIncrementalCheckpoint() throws Exception {
        var setup = setup();
        var uncertain = uncertain(setup, "uncertain", request(START, START, START, SyncJobDefinition.Mode.INCREMENTAL));
        when(setup.port.calendarVersion()).thenReturn(CALENDAR, CALENDAR, CALENDAR, "calendar-changed");
        assertThrows(IllegalStateException.class, () -> setup.owner.reconcile("uncertain", true));
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get("uncertain").state());
        assertTrue(uncertain.locks.findOwned("uncertain", uncertain.lease.scope()).inDoubt());
        verify(setup.port, never()).send(anyList());
    }

    @Test void aDisabledOrUnadmittedFormalPortRejectsBeforeQueryingOrSending() {
        var jdbc = mock(JdbcTemplate.class);
        var properties = new QuestDbProperties();
        var disabled = new MarketBreadthDailyV1MaterializationPort(jdbc, properties, false);
        disabled.bind(START, START, CURRENT);
        assertThrows(IllegalStateException.class, disabled::preflight);
        var unadmitted = new MarketBreadthDailyV1MaterializationPort(jdbc, properties, true);
        unadmitted.bind(START, START, CURRENT);
        assertThrows(IllegalStateException.class, unadmitted::preflight);
        verifyNoInteractions(jdbc);
    }

    @Test void isolatedFullRepairRunsThroughCanonicalFrozenMaterializationAndLedger() throws Exception {
        var setup = setup();
        Snapshot invalid = new Snapshot(CURRENT.sourceId(), CURRENT.sourceDirectory(), CURRENT.sourceTableTxn(),
                CURRENT.sourceSeqTxn(), CURRENT.sourceWriterTxn(), true, CURRENT.mvId(), CURRENT.mvDirectory(),
                CURRENT.mvTxn(), CURRENT.mvSeqTxn(), CURRENT.mvWriterTxn(), true, false, false,
                CURRENT.definitionSha(), CURRENT.refreshStarted(), CURRENT.refreshFinished(),
                CURRENT.refreshBaseTxn(), CURRENT.reportedBaseTxn(), CURRENT.sourcePartition(), "invalid");
        var observed = new java.util.concurrent.atomic.AtomicReference<>(invalid);
        when(setup.port.snapshot()).thenAnswer(call -> observed.get());
        doAnswer(call -> { observed.set(CURRENT); return null; }).when(setup.port).send(ROWS);
        var repaired = setup.owner.repairIsolated();
        assertEquals(SyncRunState.VERIFIED, repaired.result().state());
        assertEquals(2, repaired.sourceRawRows());
        assertEquals(CURRENT, repaired.target());
        assertNull(repaired.targetSnapshotError());
        var frozen = com.zoutrankil.data.domain.JobDefinitionJson.mapper()
                .readTree(setup.ledger.getRun(repaired.result().runId()).frozenJson());
        assertEquals("MATERIALIZE", frozen.path("mode").asText());
        assertEquals("FULL_ISOLATED", frozen.path("parameters").path("native_refresh").asText());
        assertEquals(START.toString(), frozen.path("from").asText());
        assertEquals(START.toString(), frozen.path("to").asText());
        assertEquals(1, setup.ledger.entries(repaired.result().runId(), null, 100).stream()
                .filter(entry -> entry.kind() == SyncRunLedger.Kind.SLICE).count());
        verify(setup.port).configureFullIsolated();
        verify(setup.port).send(ROWS);
    }

    @Test void acknowledgedFullRefreshVisibilityTimeoutRetainsLeaseAndCannotBeRepeated() throws Exception {
        var setup = setup();
        when(setup.port.walSettled()).thenReturn(false);
        when(setup.port.unresolved()).thenReturn(true);
        var uncertain = setup.owner.repairIsolated();
        assertFullUncertaintyBlocksReplay(setup, uncertain);
    }

    @Test void lostFullRefreshAcknowledgementRetainsDurableSubmittedIntentAndCannotBeRepeated() throws Exception {
        var setup = setup();
        doThrow(new IllegalStateException("simulated lost native refresh acknowledgement")).when(setup.port).send(ROWS);
        when(setup.port.uncertainSenderStopped()).thenReturn(false);
        when(setup.port.unresolved()).thenReturn(true);
        var uncertain = setup.owner.repairIsolated();
        assertFullUncertaintyBlocksReplay(setup, uncertain);
        verify(setup.port, never()).readback(anyList());
    }

    @Test void defaultDisabledFullRepairCannotRunAFormalStatement() {
        var jdbc = mock(JdbcTemplate.class);
        var disabled = new MarketBreadthDailyV1MaterializationPort(jdbc, new QuestDbProperties(), false);
        assertThrows(IllegalStateException.class, disabled::configureFullIsolated);
        assertThrows(IllegalStateException.class, disabled::fullSourceScope);
        var admittedFormal = new MarketBreadthDailyV1MaterializationPort(jdbc, new QuestDbProperties(), true, TARGET);
        assertThrows(IllegalStateException.class, admittedFormal::configureFullIsolated);
        verifyNoInteractions(jdbc);
    }

    @Test void fullRepairRejectsEmptyAndUnboundedWholeSourceScopes() {
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBreadthDailyV1MaterializationPort.FullSourceScope(START, START, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBreadthDailyV1MaterializationPort.FullSourceScope(START, START, 200_001));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketBreadthDailyV1MaterializationPort.FullSourceScope(START, START.plusDays(31), 2));
    }

    @Test void durableRunResultRemainsVisibleWhenItsDecorativeSnapshotCannotBeRead() throws Exception {
        var setup = setup();
        doThrow(new IllegalStateException("not admitted")).when(setup.port).preflight();
        // Planning has already fixed its source, so failure here is only the post-run decoration.
        when(setup.port.snapshot()).thenThrow(new IllegalStateException("private metadata unavailable"));
        var saved = request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE);
        var failed = setup.owner.run(new MarketBreadthDailyV1JobService.Plan(saved, TARGET, CURRENT));
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

    private static void assertFullUncertaintyBlocksReplay(Setup setup,
            MarketBreadthDailyV1JobService.MaterializationResult uncertain) throws Exception {
        assertEquals(SyncRunState.IN_DOUBT, uncertain.result().state());
        String id = uncertain.result().runId();
        var lock = new DatasetIntervalLock(setup.path);
        var scope = DatasetIntervalLock.Scope.allDates("mv_market_breadth_daily_v1");
        var lease = lock.findOwned(id, scope);
        assertNotNull(lease);
        assertTrue(lease.inDoubt());
        var slice = setup.ledger.entries(id, null, 100).stream()
                .filter(entry -> entry.kind() == SyncRunLedger.Kind.SLICE).findFirst().orElseThrow();
        assertEquals(SyncRunState.IN_DOUBT, slice.state());
        assertTrue(setup.ledger.events(slice.id(), -1, 20).stream()
                .anyMatch(event -> event.state() == SyncRunState.SUBMITTED));
        var frozen = com.zoutrankil.data.domain.JobDefinitionJson.mapper().readTree(setup.ledger.getRun(id).frozenJson());
        assertEquals("FULL_ISOLATED", frozen.path("parameters").path("native_refresh").asText());
        assertThrows(IllegalStateException.class, () -> setup.owner.resume(id));
        var replay = setup.owner.repairIsolated();
        assertEquals(SyncRunState.FAILED, replay.result().state());
        assertEquals("DATASET_INTERVAL_BUSY", replay.result().errorCode());
        assertTrue(lock.findOwned(id, scope).inDoubt());
        verify(setup.port, times(1)).send(ROWS);
    }

    private void assertPostReadbackDrift(Snapshot changed) throws Exception {
        var setup = setup();
        var uncertain = uncertain(setup, "uncertain", request(START, START, START, SyncJobDefinition.Mode.MATERIALIZE));
        when(setup.port.snapshot()).thenReturn(CURRENT, CURRENT, CURRENT, CURRENT, changed);
        assertThrows(IllegalStateException.class, () -> setup.owner.reconcile("uncertain", true));
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get("uncertain").state());
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get(uncertain.slice).state());
        assertTrue(uncertain.locks.findOwned("uncertain", uncertain.lease.scope()).inDoubt());
        verify(setup.port, never()).send(anyList());
    }

    private record Setup(Path path, SyncRunLedger ledger, MarketBreadthDailyV1MaterializationPort port,
                         MarketBreadthDailyV1JobService owner) {}
    private Setup setup() throws Exception {
        Path path = temporary.resolve("ledger-" + java.util.UUID.randomUUID() + ".sqlite3");
        var ledger = new SyncRunLedger(path);
        var port = mock(MarketBreadthDailyV1MaterializationPort.class);
        configure(port);
        return new Setup(path, ledger, port, new MarketBreadthDailyV1JobService(path, () -> port));
    }

    private static void configure(MarketBreadthDailyV1MaterializationPort port) {
        when(port.snapshot()).thenReturn(CURRENT);
        when(port.targetId()).thenReturn(TARGET);
        when(port.calendarVersion()).thenReturn(CALENDAR);
        when(port.sourceRawRows(any(LocalDate.class), any(LocalDate.class))).thenReturn(2L);
        when(port.expected(any(LocalDate.class), any(LocalDate.class))).thenReturn(ROWS);
        when(port.actual(any(LocalDate.class), any(LocalDate.class))).thenReturn(ROWS);
        when(port.readback(anyList())).thenReturn(ROWS);
        when(port.walSettled()).thenReturn(true);
        when(port.verifiedSnapshot()).thenReturn(CURRENT);
        when(port.unresolved()).thenReturn(false);
        when(port.visibilityTimeout()).thenReturn(Duration.ofMillis(20));
        when(port.fullSourceScope()).thenReturn(new MarketBreadthDailyV1MaterializationPort.FullSourceScope(START, START, 2));
        when(port.outputRowCount()).thenReturn(1L);
    }

    private static SyncJobDefinition.FrozenRequest request(LocalDate anchor, LocalDate from, LocalDate to,
                                                            SyncJobDefinition.Mode mode) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("source_version", CURRENT.sourceVersion());
        parameters.put("target_id", TARGET);
        parameters.put("bootstrap_from", anchor);
        if (mode == SyncJobDefinition.Mode.INCREMENTAL) parameters.put("calendar_version", CALENDAR);
        return MarketBreadthDailyV1JobService.definition().freeze(mode, parameters, from, to, LOGICAL);
    }

    private static void verified(SyncRunLedger ledger, String runId, SyncJobDefinition.FrozenRequest request,
                                 String targetId) throws Exception {
        ledger.createRun(runId, null, targetId, request);
        ledger.transition(runId, 0, SyncRunState.RUNNING, "{}");
        ledger.transition(runId, 1, SyncRunState.VERIFIED, proof(false));
    }

    private record Uncertain(String runId, String attempt, String slice, DatasetIntervalLock locks,
                             DatasetIntervalLock.Lease lease) {}
    private static Uncertain uncertain(Setup setup, String id, SyncJobDefinition.FrozenRequest request) throws Exception {
        return uncertain(setup, id, request, DatasetIntervalLock.Scope.allDates("mv_market_breadth_daily_v1"));
    }

    private static Uncertain uncertain(Setup setup, String id, SyncJobDefinition.FrozenRequest request,
                                       DatasetIntervalLock.Scope scope) throws Exception {
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

    private static void completeUncertain(SyncRunLedger ledger, Uncertain uncertain) throws Exception {
        ledger.transition(uncertain.slice, ledger.get(uncertain.slice).revision(), SyncRunState.VERIFIED, proof(true));
        ledger.transition(uncertain.attempt, ledger.get(uncertain.attempt).revision(), SyncRunState.VERIFIED, proof(true));
        ledger.transition(uncertain.runId, ledger.get(uncertain.runId).revision(), SyncRunState.VERIFIED, proof(true));
    }

    private static String proof(boolean stopped) throws Exception {
        return com.zoutrankil.data.domain.JobDefinitionJson.mapper().writeValueAsString(Map.of("verification", Map.of(
                "passed", true, "expectedRows", 1, "actualRows", 1, "matchedRows", 1,
                "mismatchedRows", 0, "duplicateKeys", 0, "missingKeys", 0,
                "sourceFingerprint", "unit-source", "readbackEvidence", "unit-native-readback", "writerStopped", stopped)));
    }

    private static Snapshot snapshot(long sourceTxn, long outputTxn) {
        return new Snapshot(11, "stk_factor~11", sourceTxn, sourceTxn, sourceTxn, true,
                12, "mv_market_breadth_daily_v1~12", outputTxn, outputTxn, outputTxn, true, true, true,
                MarketBreadthDailyV1MaterializationPort.DEFINITION_SHA, "2026-10-06T00:00:00Z", "2026-10-06T00:00:01Z",
                sourceTxn, sourceTxn, "YEAR", "valid");
    }
}
