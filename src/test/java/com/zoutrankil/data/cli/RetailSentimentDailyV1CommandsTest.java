package com.zoutrankil.data.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.RetailSentimentDailyV1MaterializationPort;
import com.zoutrankil.data.service.RetailSentimentDailyV1JobService;
import com.zoutrankil.data.service.SyncJobRunner;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Strict canonical command boundaries; commands do not invent windows or retry uncertain refreshes. */
class RetailSentimentDailyV1CommandsTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 17);
    private static final String TARGET = "questdb-" + "a".repeat(64);
    private static final RetailSentimentDailyV1MaterializationPort.Snapshot SNAPSHOT =
            new RetailSentimentDailyV1MaterializationPort.Snapshot(11, "l2_daily_features~11", 7,
                    7, 7, true, 12, "mv_retail_sentiment_daily_v1~12", 10, 4, 4, true,
                    true, true, RetailSentimentDailyV1MaterializationPort.DEFINITION_SHA,
                    "2026-10-06T00:00:00Z", "2026-10-06T00:00:01Z", 7, 7, "DAY", "valid");

    private static RetailSentimentDailyV1JobService.MaterializationResult result(SyncRunState state) {
        return new RetailSentimentDailyV1JobService.MaterializationResult(
                new SyncJobRunner.Result("d098-test", state, 1, state == SyncRunState.VERIFIED ? 1 : 0, null),
                1, SNAPSHOT, SNAPSHOT, null);
    }

    private static RetailSentimentDailyV1JobService.Plan plan() {
        var request = RetailSentimentDailyV1JobService.definition().freeze(SyncJobDefinition.Mode.MATERIALIZE,
                Map.of("source_version", SNAPSHOT.sourceVersion(), "target_id", TARGET, "bootstrap_from", DATE),
                DATE, DATE, DATE);
        return new RetailSentimentDailyV1JobService.Plan(request, TARGET, SNAPSHOT);
    }

    @Test void planRequiresFiniteExplicitWindowAndDoesNotExecute() throws Exception {
        var owner = mock(RetailSentimentDailyV1JobService.class);
        var plan = plan();
        when(owner.plan(DATE, DATE, DATE, SyncJobDefinition.Mode.MATERIALIZE)).thenReturn(plan);
        RetailSentimentDailyV1Commands.execute("plan-retail-sentiment-daily-job",
                Map.of("--from", DATE.toString(), "--to", DATE.toString(), "--logical-date", DATE.toString(),
                        "--mode", "MATERIALIZE"), owner);
        verify(owner).plan(DATE, DATE, DATE, SyncJobDefinition.Mode.MATERIALIZE);
        verify(owner, never()).run(any());
    }

    @Test void runExecutesTheExactPlanAndAcceptsOnlyVerifiedCompletion() throws Exception {
        var owner = mock(RetailSentimentDailyV1JobService.class);
        var plan = plan();
        when(owner.plan(DATE, DATE, DATE, null)).thenReturn(plan);
        when(owner.run(plan)).thenReturn(result(SyncRunState.VERIFIED));
        RetailSentimentDailyV1Commands.execute("run-retail-sentiment-daily-job",
                Map.of("--from", DATE.toString(), "--to", DATE.toString(), "--logical-date", DATE.toString()), owner);
        verify(owner).run(plan);
    }

    @ParameterizedTest @ValueSource(strings = {"FAILED", "IN_DOUBT", "CANCELLED"})
    void incompleteRunDoesNotReturnACommandSuccess(String state) throws Exception {
        var owner = mock(RetailSentimentDailyV1JobService.class);
        var plan = plan();
        when(owner.plan(DATE, DATE, DATE, null)).thenReturn(plan);
        when(owner.run(plan)).thenReturn(result(SyncRunState.valueOf(state)));
        assertThrows(IncompleteCommandException.class, () -> RetailSentimentDailyV1Commands.execute(
                "run-retail-sentiment-daily-job",
                Map.of("--from", DATE.toString(), "--to", DATE.toString(), "--logical-date", DATE.toString()), owner));
        verify(owner, times(1)).run(plan);
    }

    @Test void missingAndUnknownOptionsAreRejectedBeforeCallingOwner() {
        var owner = mock(RetailSentimentDailyV1JobService.class);
        assertThrows(IllegalArgumentException.class, () -> RetailSentimentDailyV1Commands.execute(
                "run-retail-sentiment-daily-job", Map.of("--from", DATE.toString(), "--to", DATE.toString()), owner));
        assertThrows(IllegalArgumentException.class, () -> RetailSentimentDailyV1Commands.execute(
                "plan-retail-sentiment-daily-job", Map.of("--from", DATE.toString(), "--to", DATE.toString(),
                        "--logical-date", DATE.toString(), "--retry-unknown", "true"), owner));
        assertThrows(IllegalArgumentException.class, () -> RetailSentimentDailyV1Commands.execute(
                "repair-retail-sentiment-daily-isolated", Map.of("--allow-formal", "true"), owner));
        assertThrows(IllegalArgumentException.class, () -> RetailSentimentDailyV1Commands.execute("unknown", Map.of(), owner));
        verifyNoInteractions(owner);
    }

    @Test void isolatedInstallationAndFullRepairUseTheirDedicatedOwnerLifecycle() throws Exception {
        var owner = mock(RetailSentimentDailyV1JobService.class);
        when(owner.installIsolated()).thenReturn(SNAPSHOT);
        when(owner.repairIsolated()).thenReturn(result(SyncRunState.VERIFIED));
        RetailSentimentDailyV1Commands.execute("install-retail-sentiment-daily-isolated", Map.of(), owner);
        RetailSentimentDailyV1Commands.execute("repair-retail-sentiment-daily-isolated", Map.of(), owner);
        verify(owner).installIsolated();
        verify(owner).repairIsolated();
        verify(owner, never()).run(any());
    }

    @Test void unknownFullAcknowledgementCannotBecomeCommandSuccess() throws Exception {
        var owner = mock(RetailSentimentDailyV1JobService.class);
        when(owner.repairIsolated()).thenReturn(result(SyncRunState.IN_DOUBT));
        assertThrows(IncompleteCommandException.class, () -> RetailSentimentDailyV1Commands.execute(
                "repair-retail-sentiment-daily-isolated", Map.of(), owner));
        verify(owner, times(1)).repairIsolated();
    }

    @Test void statusCancelAndResumeRequireAnExactRunIdentifier() throws Exception {
        var owner = mock(RetailSentimentDailyV1JobService.class);
        when(owner.status("saved")).thenReturn(new RetailSentimentDailyV1JobService.Status("saved", SyncRunState.IN_DOUBT,
                TARGET, DATE.toString(), 0, 1, false, SNAPSHOT, null));
        when(owner.cancel("saved")).thenReturn(true);
        when(owner.resume("saved")).thenReturn(result(SyncRunState.VERIFIED));
        RetailSentimentDailyV1Commands.execute("retail-sentiment-daily-job-status", Map.of("--run", "saved"), owner);
        RetailSentimentDailyV1Commands.execute("cancel-retail-sentiment-daily-run", Map.of("--run", "saved"), owner);
        RetailSentimentDailyV1Commands.execute("resume-retail-sentiment-daily-run", Map.of("--run", "saved"), owner);
        verify(owner).status("saved"); verify(owner).cancel("saved"); verify(owner).resume("saved");
        assertThrows(IllegalArgumentException.class, () -> RetailSentimentDailyV1Commands.execute(
                "resume-retail-sentiment-daily-run", Map.of(), owner));
    }

    @Test void reconciliationRequiresExplicitStoppedWriterAndNeverResubmits() throws Exception {
        var owner = mock(RetailSentimentDailyV1JobService.class);
        assertThrows(IllegalArgumentException.class, () -> RetailSentimentDailyV1Commands.execute(
                "reconcile-retail-sentiment-daily-run", Map.of("--run", "saved", "--writer-stopped", "false"), owner));
        assertThrows(IllegalArgumentException.class, () -> RetailSentimentDailyV1Commands.execute(
                "reconcile-retail-sentiment-daily-run", Map.of("--run", "saved"), owner));
        verifyNoInteractions(owner);
        when(owner.reconcile("saved", true)).thenReturn(new RetailSentimentDailyV1JobService.Status(
                "saved", SyncRunState.VERIFIED, TARGET, DATE.toString(), 1, 0, false, SNAPSHOT, null));
        RetailSentimentDailyV1Commands.execute("reconcile-retail-sentiment-daily-run",
                Map.of("--run", "saved", "--writer-stopped", "true"), owner);
        verify(owner).reconcile("saved", true);
        verify(owner, never()).run(any()); verify(owner, never()).resume(anyString());
    }
}