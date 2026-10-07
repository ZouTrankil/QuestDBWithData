package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.EtfMarketOverviewCacheDelegatedPort;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite authority and exclusion, with only the external owner boundary mocked. */
class EtfMarketOverviewDailyCacheJobServiceTest {
    @TempDir Path temporary;
    private static final LocalDate START = LocalDate.of(2026, 9, 17);
    private static final LocalDate LOGICAL = LocalDate.of(2026, 10, 6);
    private static final String SOURCE = "a".repeat(64);
    private static final String TARGET = "questdb-" + "b".repeat(64);
    private static final String GENERATION = "c".repeat(64);

    @Test void definitionHasExactlyTheExistingThreeSourceJobsAndFiniteManualBudgets() {
        var definition = EtfMarketOverviewDailyCacheJobService.definition();
        assertEquals("data.etf_market_overview_daily_cache", definition.jobId());
        assertEquals(List.of(ref(EtfShareSyncJobOwner.DEFINITION), ref(EtfDailySyncJobOwner.DEFINITION),
                ref(EtfBasicSyncJobOwner.DEFINITION)), definition.dependencies());
        assertEquals(3, definition.dependencies().getFirst().version());
        assertEquals(SyncJobDefinition.Mode.INCREMENTAL, definition.defaultMode());
        assertEquals(31, definition.budget().maxWindowDays());
        assertEquals(31, definition.budget().maxPages());
        assertEquals(31, definition.budget().maxRows());
        assertEquals(SyncJobDefinition.Frequency.MANUAL, definition.frequency());
        assertTrue(definition.enabled()); assertFalse(definition.dailyEligible());
        assertEquals(1, definition.retry().maxAttempts());
    }

    @Test void incrementalAlwaysRevalidatesTheEntireAnchorDespiteAPriorCheckpoint() throws Exception {
        var setup = setup();
        verified(setup, "first-prefix", request(START, START.plusDays(2), SyncJobDefinition.Mode.INCREMENTAL), SyncRunState.VERIFIED);
        var plan = setup.owner.plan(START, START.plusDays(4), LOGICAL, null);
        assertEquals(START, plan.request().from());
        assertEquals(START.plusDays(2), plan.request().parameters().get("checkpoint_before"));
        assertEquals(START.plusDays(4), plan.request().to());
        verify(setup.port, never()).send(anyList());
    }

    @Test void unanchoredOrDifferentSourceHistoryAndZeroSourceCannotAdvanceCheckpoint() throws Exception {
        var setup = setup();
        var values = new LinkedHashMap<String,Object>(request(START, START.plusDays(1), SyncJobDefinition.Mode.INCREMENTAL).parameters());
        values.put("source_version", "f".repeat(64));
        verified(setup, "old-basic-or-year-revision", EtfMarketOverviewDailyCacheJobService.definition().freeze(
                SyncJobDefinition.Mode.INCREMENTAL, values, START, START.plusDays(1), LOGICAL), SyncRunState.VERIFIED);
        values.put("source_version", SOURCE);
        verified(setup, "unanchored", EtfMarketOverviewDailyCacheJobService.definition().freeze(
                SyncJobDefinition.Mode.INCREMENTAL, values, START.plusDays(1), START.plusDays(2), LOGICAL), SyncRunState.VERIFIED);
        verified(setup, "no-source", request(START, START.plusDays(2), SyncJobDefinition.Mode.INCREMENTAL), SyncRunState.VERIFIED_EMPTY);
        var plan = setup.owner.plan(START, START.plusDays(3), LOGICAL, null);
        assertEquals(START, plan.request().from());
        assertFalse(plan.request().parameters().containsKey("checkpoint_before"));
    }

    @Test void checkpointCannotPermitAnOldAnchorToExceedThirtyOneDayBudget() throws Exception {
        var setup = setup();
        verified(setup, "prefix", request(START, START.plusDays(30), SyncJobDefinition.Mode.INCREMENTAL), SyncRunState.VERIFIED);
        assertThrows(IllegalArgumentException.class, () -> setup.owner.plan(START, START.plusDays(31), LOGICAL, null));
        assertThrows(IllegalArgumentException.class, () -> setup.owner.plan(START, START.minusDays(1), LOGICAL, null));
        verifyNoInteractions(setup.port);
    }

    @Test void everyKnownDateIncludingEmptyJoinIsOneDurablySubmittedUnit() throws Exception {
        var setup = setup();
        setup.days.put(START.plusDays(1), envelope(START.plusDays(1), true, null, SOURCE));
        var submitted = new ArrayList<VerifiedBatchExecutor.Submission>();
        doAnswer(call -> {
            var context = (VerifiedBatchExecutor.Submission) call.getArgument(0);
            var actual = SyncRunLedger.openReadOnly(context.ledgerPath()).get(context.sliceId());
            assertEquals(SyncRunState.SUBMITTED, actual.state());
            assertEquals(context.revision(), actual.revision());
            assertEquals(context.sourceFingerprint(), JobDefinitionJson.mapper().readTree(actual.payloadJson()).path("sourceFingerprint").asText());
            submitted.add(context); return null;
        }).when(setup.port).submissionRecorded(any());
        var result = setup.owner.run(setup.owner.plan(START, START.plusDays(1), LOGICAL, null));
        assertEquals(SyncRunState.VERIFIED, result.result().state(), result.toString());
        assertEquals(2, result.result().verifiedRows());
        assertEquals(2, submitted.size());
        verify(setup.port).send(List.of(setup.days.get(START)));
        verify(setup.port).send(List.of(setup.days.get(START.plusDays(1))));
        assertEquals(2, setup.owner.status(result.result().runId()).verifiedPublicationUnits());
        assertNull(new DatasetIntervalLock(setup.path).findOwned(result.result().runId(), scope()));
    }

    @Test void noSourceDaysAreExplicitVerifiedEmptyAndNeverInvokeTheOwner() throws Exception {
        var setup = setup(); setup.days.clear();
        var result = setup.owner.run(setup.owner.plan(START, START.plusDays(2), LOGICAL, null));
        assertEquals(SyncRunState.VERIFIED_EMPTY, result.result().state());
        assertEquals(0, result.result().verifiedRows());
        verify(setup.port, never()).send(anyList());
        verify(setup.port, never()).submissionRecorded(any());
        assertFalse(setup.owner.plan(START, START.plusDays(2), LOGICAL, null).request().parameters().containsKey("checkpoint_before"));
    }

    @Test void anUnknownFutureTailDoesNotMoveCheckpointPastTheLastActuallyVerifiedSourceDay() throws Exception {
        var setup = setup();
        var result = setup.owner.run(setup.owner.plan(START, START.plusDays(4), LOGICAL, null));
        assertEquals(SyncRunState.VERIFIED, result.result().state(), result.toString());
        assertEquals(1, result.result().verifiedRows());
        var next = setup.owner.plan(START, START.plusDays(5), LOGICAL, null);
        assertEquals(START, next.request().parameters().get("checkpoint_before"));
        assertEquals(START, next.request().from());
    }

    @Test void reconcileModeVerifiesOnlySelectsAndNeverCallsPublisherOrSubmissionHook() throws Exception {
        var setup = setup();
        var result = setup.owner.run(setup.owner.plan(START, START, LOGICAL, SyncJobDefinition.Mode.RECONCILE));
        assertEquals(SyncRunState.VERIFIED, result.result().state(), result.toString());
        verify(setup.port, never()).send(anyList());
        verify(setup.port, never()).submissionRecorded(any());
        verify(setup.port, atLeastOnce()).readback(List.of(setup.days.get(START).key()));
        String completion = setup.ledger.events(result.result().runId(), -1, 100).getLast().payloadJson();
        assertTrue(completion.contains("verification"));
        assertFalse(setup.owner.plan(START, START, LOGICAL, null).request().parameters().containsKey("checkpoint_before"));
    }

    @Test void failedRunResumePreservesEveryFrozenParameterAndParent() throws Exception {
        var setup = setup();
        doThrow(new IllegalStateException("not yet admitted")).when(setup.port).preflight();
        var failure = setup.owner.run(setup.owner.plan(START, START, LOGICAL, SyncJobDefinition.Mode.MATERIALIZE));
        assertEquals(SyncRunState.FAILED, failure.result().state());
        doNothing().when(setup.port).preflight();
        var resumed = setup.owner.resume(failure.result().runId());
        assertEquals(SyncRunState.VERIFIED, resumed.result().state(), resumed.toString());
        var original = setup.ledger.getRun(failure.result().runId());
        var child = setup.ledger.getRun(resumed.result().runId());
        assertEquals(original.id(), child.parentRunId());
        assertEquals(original.logicalDate(), child.logicalDate());
        assertEquals(SyncRequestIdentity.fingerprint(original.frozenJson(), TARGET), SyncRequestIdentity.fingerprint(child.frozenJson(), TARGET));
    }

    @Test void resumeChangedGlobalSourceFailsDurablyWithoutReplanningOrPublication() throws Exception {
        var setup = setup();
        doThrow(new IllegalStateException("disabled")).when(setup.port).preflight();
        var failure = setup.owner.run(setup.owner.plan(START, START, LOGICAL, null));
        doNothing().when(setup.port).preflight();
        setup.days.put(START, envelope(START, true, cache(START), "f".repeat(64)));
        var resumed = setup.owner.resume(failure.result().runId());
        assertEquals(SyncRunState.FAILED, resumed.result().state());
        assertEquals(0, resumed.result().verifiedRows());
        verify(setup.port, never()).send(anyList());
        assertEquals(SOURCE, JobDefinitionJson.mapper().readTree(setup.ledger.getRun(resumed.result().runId()).frozenJson())
                .path("parameters").path("source_version").asText());
    }

    @Test void terminalCancelAndVerifiedResumeDoNotAlterACompletedRun() throws Exception {
        var setup = setup();
        verified(setup, "completed", request(START, START, SyncJobDefinition.Mode.MATERIALIZE), SyncRunState.VERIFIED);
        long revision = setup.ledger.get("completed").revision();
        assertFalse(setup.owner.cancel("completed"));
        assertFalse(setup.ledger.cancellationRequested("completed"));
        assertEquals(revision, setup.ledger.get("completed").revision());
        assertThrows(IllegalStateException.class, () -> setup.owner.resume("completed"));
        verifyNoInteractions(setup.port);
    }

    @Test void canonicalPreSubmissionCancellationCanResumeWithoutAFalseVerifiedCheckpoint() throws Exception {
        var setup = setup();
        var plan = setup.owner.plan(START, START, LOGICAL, SyncJobDefinition.Mode.MATERIALIZE);
        var adapter = new EtfMarketOverviewDailyCacheMaterializeAdapter(setup.gateway, setup.port, plan.source());
        var runner = new SyncJobRunner<EtfMarketOverviewCachePublicationEnvelope,EtfMarketOverviewDailyCacheKey>(
                setup.ledger, new DatasetIntervalLock(setup.path));
        var cancelled = runner.run("cancelled-before-owner", null, TARGET, plan.request(), adapter, () -> true);
        assertEquals(SyncRunState.CANCELLED, cancelled.state());
        verify(setup.port, never()).send(anyList());
        var resumed = setup.owner.resume(cancelled.runId());
        assertEquals(SyncRunState.VERIFIED, resumed.result().state(), resumed.toString());
        assertEquals(0, resumed.result().reusedRows());
        assertEquals(cancelled.runId(), setup.ledger.getRun(resumed.result().runId()).parentRunId());
        verify(setup.port, times(1)).send(anyList());
    }

    @Test void partialResumeReReadsTheWholePrefixAndReusesOnlyActuallyVerifiedUnits() throws Exception {
        var setup = setup();
        LocalDate second = START.plusDays(1);
        setup.days.put(second, envelope(second, true, cache(second), SOURCE));
        when(setup.gateway.preview(second)).thenThrow(new IllegalStateException("readonly source unavailable"))
                .thenReturn(setup.days.get(second));
        var partial = setup.owner.run(setup.owner.plan(START, second, LOGICAL, null));
        assertEquals(SyncRunState.PARTIAL, partial.result().state(), partial.toString());
        assertEquals(1, partial.result().verifiedRows());
        var resumed = setup.owner.resume(partial.result().runId());
        assertEquals(SyncRunState.VERIFIED, resumed.result().state(), resumed.toString());
        assertEquals(2, resumed.result().verifiedRows());
        assertEquals(1, resumed.result().reusedRows());
        verify(setup.gateway, atLeast(4)).preview(START);
        verify(setup.port, times(1)).send(List.of(setup.days.get(START)));
        verify(setup.port, times(1)).send(List.of(setup.days.get(second)));
    }

    @Test void activeCancellationIsDurableWithoutInventingCompletion() throws Exception {
        var setup = setup();
        setup.ledger.createRun("active", null, TARGET, request(START, START, SyncJobDefinition.Mode.MATERIALIZE));
        setup.ledger.transition("active", 0, SyncRunState.RUNNING, "{}");
        assertTrue(setup.owner.cancel("active"));
        assertTrue(SyncRunLedger.openReadOnly(setup.path).cancellationRequested("active"));
        assertEquals(SyncRunState.RUNNING, setup.ledger.get("active").state());
    }

    @Test void unknownOwnerDeliveryRetainsWholeDatasetLockAndRejectsAnUnrelatedWindow() throws Exception {
        var setup = setup();
        doThrow(new IllegalStateException("owner ACK lost")).when(setup.port).send(anyList());
        when(setup.port.readback(anyList())).thenReturn(List.of());
        when(setup.port.uncertainSenderStopped()).thenReturn(false);
        var unknown = setup.owner.run(setup.owner.plan(START, START, LOGICAL, null));
        assertEquals(SyncRunState.IN_DOUBT, unknown.result().state());
        assertTrue(new DatasetIntervalLock(setup.path).findOwned(unknown.result().runId(), scope()).inDoubt());
        assertThrows(IllegalStateException.class, () -> setup.owner.resume(unknown.result().runId()));
        var other = setup.owner.run(setup.owner.plan(START.plusDays(8), START.plusDays(8), LOGICAL, SyncJobDefinition.Mode.MATERIALIZE));
        assertEquals(SyncRunState.FAILED, other.result().state());
        assertEquals("DATASET_INTERVAL_BUSY", other.result().errorCode());
        verify(setup.port, times(1)).send(anyList());
    }

    @Test void callerStoppedFlagCannotReleaseUnknownWithoutActualSenderProof() throws Exception {
        var setup = setup(); uncertain(setup, "uncertain");
        when(setup.port.uncertainSenderStopped()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> setup.owner.reconcile("uncertain", true));
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get("uncertain").state());
        assertTrue(new DatasetIntervalLock(setup.path).findOwned("uncertain", scope()).inDoubt());
        verify(setup.port).reconciliationContext(setup.path.toAbsolutePath().normalize(), "uncertain");
        verify(setup.port, never()).send(anyList());
    }

    @Test void unknownFirstUnitOfTwoKnownDaysCannotReconcileAnUnsubmittedPrefixOrReplay() throws Exception {
        var setup = setup(); LocalDate second = START.plusDays(1);
        var firstUnit = setup.days.get(START);
        var secondUnit = envelope(second, true, cache(second), SOURCE);
        setup.days.put(second, secondUnit);
        var submitted = new AtomicReference<VerifiedBatchExecutor.Submission>();
        var published = new HashMap<EtfMarketOverviewDailyCacheKey,EtfMarketOverviewCachePublicationEnvelope>();
        doAnswer(call -> {
            var context = (VerifiedBatchExecutor.Submission) call.getArgument(0);
            var slice = setup.ledger.get(context.sliceId());
            assertEquals(SyncRunState.SUBMITTED, slice.state()); assertEquals(4, slice.revision());
            assertEquals(context.runId(), slice.runId());
            var source = JobDefinitionJson.mapper().readTree(slice.payloadJson());
            assertEquals(START.toString(), source.required("cursor").asText());
            assertEquals(firstUnit.sourceFingerprint(), source.required("sourceFingerprint").asText());
            submitted.set(context); return null;
        }).when(setup.port).submissionRecorded(any());
        doAnswer(call -> {
            List<EtfMarketOverviewCachePublicationEnvelope> batch = call.getArgument(0);
            assertNotNull(submitted.get()); assertEquals(List.of(firstUnit), batch);
            // The mocked external boundary applied the first exact cache/receipt, then lost its ACK.
            // The actual shared SQLite SUBMITTED authority is checked above before this boundary.
            published.put(firstUnit.key(), firstUnit);
            throw new java.io.IOException("Owner ACK lost after first unit");
        }).when(setup.port).send(anyList());
        when(setup.port.readback(anyList())).thenAnswer(call -> {
            List<EtfMarketOverviewDailyCacheKey> keys = call.getArgument(0);
            return keys.stream().map(published::get).filter(Objects::nonNull).toList();
        });
        when(setup.port.uncertainSenderStopped()).thenReturn(false);
        var original = setup.owner.run(setup.owner.plan(START, second, LOGICAL, null));
        String runId = original.result().runId();
        assertEquals(SyncRunState.IN_DOUBT, original.result().state());
        assertEquals(0, original.result().verifiedRows());
        assertEquals(runId, submitted.get().runId());
        var firstSlice = setup.ledger.get(submitted.get().sliceId());
        assertEquals(SyncRunState.IN_DOUBT, firstSlice.state());
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get(firstSlice.parentId()).state());
        assertEquals(1, setup.ledger.entries(runId, null, 100).stream()
                .filter(entry -> entry.kind() == SyncRunLedger.Kind.SLICE).count());
        verify(setup.gateway, never()).preview(second);
        assertEquals(Set.of(firstUnit.key()), published.keySet());
        var locks = new DatasetIntervalLock(setup.path); var lease = locks.findOwned(runId, scope());
        assertNotNull(lease); assertTrue(lease.inDoubt());
        var entriesBefore = setup.ledger.entries(runId, null, 100);
        var sliceEventsBefore = setup.ledger.events(firstSlice.id(), -1, 100);

        // Exact first-unit values and real stopped-writer proof cannot invent the missing second unit.
        when(setup.port.uncertainSenderStopped()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> setup.owner.reconcile(runId, true));
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get(runId).state());
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get(firstSlice.id()).state());
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get(firstSlice.parentId()).state());
        assertEquals(entriesBefore, setup.ledger.entries(runId, null, 100));
        assertEquals(sliceEventsBefore, setup.ledger.events(firstSlice.id(), -1, 100));
        assertEquals(lease, locks.findOwned(runId, scope()));
        verify(setup.port).reconciliationContext(setup.path.toAbsolutePath().normalize(), runId);
        verify(setup.port).readback(List.of(firstUnit.key()));
        verify(setup.port).readback(List.of(secondUnit.key()));
        verify(setup.gateway).preview(second);
        assertThrows(IllegalStateException.class, () -> setup.owner.resume(runId));
        assertEquals(lease, locks.findOwned(runId, scope()));
        verify(setup.port, times(1)).send(anyList()); verify(setup.port, times(1)).submissionRecorded(any());
        verify(setup.port, never()).send(List.of(secondUnit));
        assertEquals(Set.of(firstUnit.key()), published.keySet());
    }

    @Test void unknownFirstHitCanReconcileTwoFreshCompleteDaysWithoutInventingASecondSubmission() throws Exception {
        var setup = setup(); LocalDate second = START.plusDays(1);
        var firstUnit = setup.days.get(START);
        var secondUnit = envelope(second, true, cache(second), SOURCE);
        setup.days.put(second, secondUnit);
        // Both complete current cache/receipt units already exist, as after a verified FIRST publication.
        var stored = new HashMap<EtfMarketOverviewDailyCacheKey,EtfMarketOverviewCachePublicationEnvelope>();
        stored.put(firstUnit.key(), firstUnit); stored.put(secondUnit.key(), secondUnit);
        var storedBefore = Map.copyOf(stored);
        var submitted = new AtomicReference<VerifiedBatchExecutor.Submission>();
        doAnswer(call -> {
            var context = (VerifiedBatchExecutor.Submission) call.getArgument(0);
            var entry = setup.ledger.get(context.sliceId());
            assertEquals(SyncRunState.SUBMITTED, entry.state()); assertEquals(4, entry.revision());
            assertEquals(context.runId(), entry.runId());
            var page = JobDefinitionJson.mapper().readTree(entry.payloadJson());
            assertEquals(START.toString(), page.required("cursor").asText());
            assertEquals(firstUnit.sourceFingerprint(), page.required("sourceFingerprint").asText());
            submitted.set(context); return null;
        }).when(setup.port).submissionRecorded(any());
        doAnswer(call -> {
            List<EtfMarketOverviewCachePublicationEnvelope> rows = call.getArgument(0);
            assertNotNull(submitted.get()); assertEquals(List.of(firstUnit), rows);
            assertEquals(storedBefore, stored); // Actual first hit has no newly applied cache/receipt rows.
            throw new java.io.IOException("Owner hit completed but process termination acknowledgement is unknown");
        }).when(setup.port).send(anyList());
        when(setup.port.readback(anyList())).thenAnswer(call -> {
            List<EtfMarketOverviewDailyCacheKey> keys = call.getArgument(0);
            return keys.stream().map(stored::get).filter(Objects::nonNull).toList();
        });
        when(setup.port.uncertainSenderStopped()).thenReturn(false);
        var original = setup.owner.run(setup.owner.plan(START, second, LOGICAL, null));
        String runId = original.result().runId();
        assertEquals(SyncRunState.IN_DOUBT, original.result().state());
        assertEquals(1, original.result().sourceRows()); assertEquals(0, original.result().verifiedRows());
        assertEquals(runId, submitted.get().runId());
        var entriesBefore = setup.ledger.entries(runId, null, 100);
        assertEquals(3, entriesBefore.size());
        assertTrue(entriesBefore.stream().allMatch(entry -> entry.state() == SyncRunState.IN_DOUBT));
        var slice = entriesBefore.stream().filter(entry -> entry.kind() == SyncRunLedger.Kind.SLICE).findFirst().orElseThrow();
        assertEquals(1, entriesBefore.stream().filter(entry -> entry.kind() == SyncRunLedger.Kind.SLICE).count());
        assertEquals(5, slice.revision()); assertEquals(submitted.get().sliceId(), slice.id());
        var eventsBefore = new LinkedHashMap<String,List<SyncRunLedger.Event>>();
        for (var entry : entriesBefore) eventsBefore.put(entry.id(), setup.ledger.events(entry.id(), -1, 100));
        assertEquals(List.of(SyncRunState.PENDING, SyncRunState.RUNNING, SyncRunState.FETCHED,
                SyncRunState.VALIDATED, SyncRunState.SUBMITTED, SyncRunState.IN_DOUBT),
                eventsBefore.get(slice.id()).stream().map(SyncRunLedger.Event::state).toList());
        assertEquals(4, eventsBefore.get(slice.id()).get(4).revision());
        verify(setup.gateway, never()).preview(second);
        var locks = new DatasetIntervalLock(setup.path); var lease = locks.findOwned(runId, scope());
        assertNotNull(lease); assertTrue(lease.inDoubt()); assertEquals(scope(), lease.scope());
        assertEquals(storedBefore, stored);

        // Stop proof alone does not settle the run: reconciliation freshly previews and reads both days.
        when(setup.port.uncertainSenderStopped()).thenReturn(true);
        var result = setup.owner.reconcile(runId, true);
        assertEquals(SyncRunState.VERIFIED, result.state());
        assertEquals(1, result.verifiedPublicationUnits()); assertEquals(0, result.unresolvedSlices());
        assertNull(locks.findOwned(runId, scope()));
        var entriesAfter = setup.ledger.entries(runId, null, 100);
        assertEquals(3, entriesAfter.size());
        assertEquals(new HashSet<>(entriesBefore.stream().map(SyncRunLedger.Entry::id).toList()),
                new HashSet<>(entriesAfter.stream().map(SyncRunLedger.Entry::id).toList()));
        for (var entry : entriesAfter) {
            assertEquals(SyncRunState.VERIFIED, entry.state());
            var previous = entriesBefore.stream().filter(value -> value.id().equals(entry.id())).findFirst().orElseThrow();
            assertEquals(previous.revision() + 1, entry.revision());
            var events = setup.ledger.events(entry.id(), -1, 100);
            assertEquals(eventsBefore.get(entry.id()), events.subList(0, eventsBefore.get(entry.id()).size()));
            assertEquals(eventsBefore.get(entry.id()).size() + 1, events.size());
            var proof = JobDefinitionJson.mapper().readTree(entry.payloadJson()).required("verification");
            long units = entry.kind() == SyncRunLedger.Kind.SLICE ? 1 : 2;
            assertTrue(proof.required("passed").asBoolean()); assertTrue(proof.required("writerStopped").asBoolean());
            assertEquals(units, proof.required("expectedRows").asLong()); assertEquals(units, proof.required("matchedRows").asLong());
            assertEquals(units, proof.required("actualRows").asLong());
        }
        verify(setup.gateway).preview(second);
        verify(setup.port, times(2)).readback(List.of(firstUnit.key()));
        verify(setup.port, times(2)).readback(List.of(secondUnit.key()));
        verify(setup.port, atLeast(4)).walSettled();
        verify(setup.port).reconciliationContext(setup.path.toAbsolutePath().normalize(), runId);
        verify(setup.port, times(1)).submissionRecorded(any()); verify(setup.port, times(1)).send(anyList());
        verify(setup.port, never()).send(List.of(secondUnit));
        assertEquals(storedBefore, stored);
    }

    @Test void realStoppedAndExactReadOnlyProofReconcilesUnknownAndReleasesLease() throws Exception {
        var setup = setup(); uncertain(setup, "uncertain");
        when(setup.port.uncertainSenderStopped()).thenReturn(true);
        var result = setup.owner.reconcile("uncertain", true);
        assertEquals(SyncRunState.VERIFIED, result.state());
        assertEquals(1, result.verifiedPublicationUnits());
        assertNull(new DatasetIntervalLock(setup.path).findOwned("uncertain", scope()));
        verify(setup.port, never()).send(anyList());
        verify(setup.port, never()).submissionRecorded(any());
    }

    @Test void alreadyVerifiedOutcomeWithRetainedLeaseCanBeReverifiedAndReleased() throws Exception {
        var setup = setup(); uncertain(setup, "uncertain");
        when(setup.port.uncertainSenderStopped()).thenReturn(true);
        setup.ledger.transition("uncertain-slice", setup.ledger.get("uncertain-slice").revision(), SyncRunState.VERIFIED, proof(1));
        setup.ledger.transition("uncertain-attempt", setup.ledger.get("uncertain-attempt").revision(), SyncRunState.VERIFIED, proof(1));
        setup.ledger.transition("uncertain", setup.ledger.get("uncertain").revision(), SyncRunState.VERIFIED, proof(1));
        assertEquals(SyncRunState.VERIFIED, setup.owner.reconcile("uncertain", true).state());
        assertNull(new DatasetIntervalLock(setup.path).findOwned("uncertain", scope()));
        verify(setup.port, never()).send(anyList());
    }

    @Test void reconciliationCanFinishSubmittedSliceAndRunningAttemptAfterAnOutcomePersistenceFailure() throws Exception {
        var setup = setup(); uncertain(setup, "uncertain", false);
        when(setup.port.uncertainSenderStopped()).thenReturn(true);
        assertEquals(SyncRunState.SUBMITTED, setup.ledger.get("uncertain-slice").state());
        assertEquals(SyncRunState.RUNNING, setup.ledger.get("uncertain-attempt").state());
        assertEquals(SyncRunState.VERIFIED, setup.owner.reconcile("uncertain", true).state());
        assertEquals(SyncRunState.VERIFIED, setup.ledger.get("uncertain-slice").state());
        assertEquals(SyncRunState.VERIFIED, setup.ledger.get("uncertain-attempt").state());
        assertNull(new DatasetIntervalLock(setup.path).findOwned("uncertain", scope()));
        verify(setup.port, never()).send(anyList());
    }

    @Test void sourceDriftDuringReconciliationKeepsUnknownAndExclusion() throws Exception {
        var setup = setup(); uncertain(setup, "uncertain");
        when(setup.port.uncertainSenderStopped()).thenReturn(true);
        var revised = envelope(START, true, cache(START), "f".repeat(64));
        when(setup.gateway.preview(START)).thenReturn(setup.days.get(START), revised);
        assertThrows(IllegalStateException.class, () -> setup.owner.reconcile("uncertain", true));
        assertEquals(SyncRunState.IN_DOUBT, setup.ledger.get("uncertain").state());
        assertTrue(new DatasetIntervalLock(setup.path).findOwned("uncertain", scope()).inDoubt());
        verify(setup.port, never()).send(anyList());
    }

    @Test void decorativeMetadataFailureDoesNotHideADurableRunOrStatus() throws Exception {
        var setup = setup();
        doThrow(new IllegalStateException("preflight disabled")).when(setup.port).preflight();
        var initial = setup.days.get(START);
        when(setup.gateway.preview(START)).thenReturn(initial, initial).thenThrow(new IllegalStateException("metadata unavailable"));
        var result = setup.owner.run(setup.owner.plan(START, START, LOGICAL, null));
        assertEquals(SyncRunState.FAILED, result.result().state());
        assertEquals("IllegalStateException", result.targetSnapshotError());
        assertEquals(SyncRunState.FAILED, setup.ledger.get(result.result().runId()).state());
        var status = setup.owner.status(result.result().runId());
        assertEquals(result.result().runId(), status.runId());
        assertEquals("IllegalStateException", status.currentTargetError());
    }

    private Setup setup() throws Exception {
        Path path = temporary.resolve(UUID.randomUUID() + ".sqlite3");
        var gateway = mock(EtfMarketOverviewCacheOwnerGateway.class);
        var port = mock(EtfMarketOverviewCacheDelegatedPort.class);
        var days = new LinkedHashMap<LocalDate,EtfMarketOverviewCachePublicationEnvelope>();
        days.put(START, envelope(START, true, cache(START), SOURCE));
        when(gateway.preview(any(LocalDate.class))).thenAnswer(call -> {
            LocalDate day = call.getArgument(0);
            return days.getOrDefault(day, envelope(day, false, null, SOURCE));
        });
        AtomicReference<EtfMarketOverviewCachePublicationEnvelope> bound = new AtomicReference<>();
        doAnswer(call -> { bound.set(call.getArgument(0)); return null; }).when(port).bind(any());
        when(port.readback(anyList())).thenAnswer(call -> {
            List<EtfMarketOverviewDailyCacheKey> keys = call.getArgument(0);
            return keys.size() == 1 && bound.get() != null && keys.getFirst().equals(bound.get().key())
                    ? List.of(bound.get()) : List.of();
        });
        when(port.walSettled()).thenReturn(true);
        when(port.visibilityTimeout()).thenReturn(Duration.ofMillis(30));
        var owner = new EtfMarketOverviewDailyCacheJobService(path, gateway, ignored -> port);
        return new Setup(path, new SyncRunLedger(path), gateway, port, owner, days);
    }

    private record Setup(Path path, SyncRunLedger ledger, EtfMarketOverviewCacheOwnerGateway gateway,
            EtfMarketOverviewCacheDelegatedPort port, EtfMarketOverviewDailyCacheJobService owner,
            Map<LocalDate,EtfMarketOverviewCachePublicationEnvelope> days) {}

    private static EtfMarketOverviewCachePublicationEnvelope envelope(LocalDate day, boolean known,
            EtfMarketOverviewDailyCache cache, String physicalVersion) {
        var envelope = mock(EtfMarketOverviewCachePublicationEnvelope.class);
        when(envelope.tradeDate()).thenReturn(day); when(envelope.sourceVersion()).thenReturn(GENERATION);
        when(envelope.key()).thenReturn(new EtfMarketOverviewDailyCacheKey(day, GENERATION));
        when(envelope.cache()).thenReturn(cache);
        when(envelope.receipt()).thenReturn(known ? new MarketBarometerCacheCoverage(day,
                "etf_market_overview_daily", GENERATION, cache == null ? 0 : 1, "d".repeat(64)) : null);
        when(envelope.sources()).thenReturn(Map.of()); when(envelope.targets()).thenReturn(Map.of());
        when(envelope.sourcesFingerprint()).thenReturn(physicalVersion);
        when(envelope.physicalVersion()).thenReturn(physicalVersion);
        when(envelope.targetId()).thenReturn(TARGET);
        when(envelope.sourceFingerprint()).thenReturn(unitFingerprint(day, physicalVersion));
        when(envelope.responseEvidence()).thenReturn("{\"preview_path\":\"bounded-real-owner-preview.json\",\"preview_sha256\":\"" + "e".repeat(64) + "\"}");
        when(envelope.sourceRows()).thenReturn(known ? 1L : 0L);
        when(envelope.knownSourceDate()).thenReturn(known);
        return envelope;
    }

    private static EtfMarketOverviewDailyCache cache(LocalDate day) {
        return new EtfMarketOverviewDailyCache(day, 2, 123.5, 0.0247, GENERATION);
    }
    private static SyncJobDefinition.JobRef ref(SyncJobDefinition value) { return new SyncJobDefinition.JobRef(value.jobId(), value.version()); }
    private static DatasetIntervalLock.Scope scope() { return DatasetIntervalLock.Scope.allDates("etf_market_overview_daily_cache"); }
    private static SyncJobDefinition.FrozenRequest request(LocalDate from, LocalDate to, SyncJobDefinition.Mode mode) {
        return EtfMarketOverviewDailyCacheJobService.definition().freeze(mode,
                Map.of("source_version", SOURCE, "target_id", TARGET, "bootstrap_from", START), from, to, LOGICAL);
    }
    private static void verified(Setup setup, String runId, SyncJobDefinition.FrozenRequest request, SyncRunState state) throws Exception {
        setup.ledger.createRun(runId, null, TARGET, request);
        setup.ledger.transition(runId, 0, SyncRunState.RUNNING, "{}");
        setup.ledger.createChild(runId + "-attempt", SyncRunLedger.Kind.ATTEMPT, runId, runId);
        setup.ledger.transition(runId + "-attempt", 0, SyncRunState.RUNNING, "{}");
        if (state == SyncRunState.VERIFIED) {
            setup.ledger.createChild(runId + "-slice", SyncRunLedger.Kind.SLICE, runId, runId + "-attempt");
            setup.ledger.transition(runId + "-slice", 0, SyncRunState.RUNNING, "{}");
            String fingerprint = unitFingerprint(request.to(), request.parameters().get("source_version").toString());
            String page = JobDefinitionJson.mapper().writeValueAsString(Map.of("returnedRows", 1,
                    "cursor", request.to().toString(), "sourceFingerprint", fingerprint));
            setup.ledger.transition(runId + "-slice", 1, SyncRunState.FETCHED, page);
            setup.ledger.transition(runId + "-slice", 2, SyncRunState.VALIDATED, page);
            setup.ledger.transition(runId + "-slice", 3, SyncRunState.VERIFIED, proof(1, fingerprint));
        }
        String outcome = state == SyncRunState.VERIFIED_EMPTY
                ? "{\"sourceComplete\":true,\"returnedRows\":0,\"submittedRows\":0,\"responseEvidence\":\"source-empty\"}" : proof(1);
        setup.ledger.transition(runId + "-attempt", 1, state, outcome);
        setup.ledger.transition(runId, 1, state, outcome);
    }
    private static String proof(int count) throws Exception {
        return proof(count, unitFingerprint(START, SOURCE));
    }
    private static String proof(int count, String fingerprint) throws Exception {
        return JobDefinitionJson.mapper().writeValueAsString(Map.of("verification", Map.of("passed", true,
                "expectedRows", count, "actualRows", count, "matchedRows", count, "mismatchedRows", 0,
                "duplicateKeys", 0, "missingKeys", 0, "sourceFingerprint", fingerprint,
                "readbackEvidence", "actual-boundary", "writerStopped", true)));
    }
    private static String unitFingerprint(LocalDate day, String physicalVersion) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((day + "/" + physicalVersion).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void uncertain(Setup setup, String runId) throws Exception {
        uncertain(setup, runId, true);
    }
    private static void uncertain(Setup setup, String runId, boolean uncertainChildrenPersisted) throws Exception {
        setup.ledger.createRun(runId, null, TARGET, request(START, START, SyncJobDefinition.Mode.MATERIALIZE));
        setup.ledger.transition(runId, 0, SyncRunState.RUNNING, "{}");
        setup.ledger.createChild(runId + "-attempt", SyncRunLedger.Kind.ATTEMPT, runId, runId);
        setup.ledger.transition(runId + "-attempt", 0, SyncRunState.RUNNING, "{}");
        setup.ledger.createChild(runId + "-slice", SyncRunLedger.Kind.SLICE, runId, runId + "-attempt");
        setup.ledger.transition(runId + "-slice", 0, SyncRunState.RUNNING, "{}");
        String fetched = JobDefinitionJson.mapper().writeValueAsString(Map.of("returnedRows", 1,
                "sourceFingerprint", EtfMarketOverviewCacheDelegatedPort.fingerprint(setup.days.get(START)),
                "responseEvidence", setup.days.get(START).responseEvidence()));
        setup.ledger.transition(runId + "-slice", 1, SyncRunState.FETCHED, fetched);
        setup.ledger.transition(runId + "-slice", 2, SyncRunState.VALIDATED, fetched);
        setup.ledger.transition(runId + "-slice", 3, SyncRunState.SUBMITTED, fetched);
        if (uncertainChildrenPersisted) {
            setup.ledger.transition(runId + "-slice", 4, SyncRunState.IN_DOUBT, "{\"ownerDelivery\":\"UNKNOWN\"}");
            setup.ledger.transition(runId + "-attempt", 1, SyncRunState.IN_DOUBT, "{}");
        }
        setup.ledger.transition(runId, 1, SyncRunState.IN_DOUBT, "{}");
        var locks = new DatasetIntervalLock(setup.path); locks.retainInDoubt(locks.acquire(runId, scope()));
    }
}
