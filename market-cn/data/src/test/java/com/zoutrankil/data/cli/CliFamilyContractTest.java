package com.zoutrankil.data.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.StockBasicDataset;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.stock.application.DailyBasicJobService;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.l2.application.L2DailyFeaturesJobService;
import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockBasicSyncAdapter;
import com.zoutrankil.data.stock.application.StockBasicSyncService;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.SyncJobRunner;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ResourceLock("java.lang.System.out")
class CliFamilyContractTest {
    @ParameterizedTest @ValueSource(strings = {"PARTIAL", "FAILED", "IN_DOUBT", "CANCELLED"})
    void stockResumePrintsPersistedResultBeforeReturningIncompleteExit(String state) throws Exception {
        var service = mock(DailyBasicJobService.class);
        var result = new SyncJobRunner.Result("run-preserved", SyncRunState.valueOf(state), 12, 7, null, 3);
        when(service.resume("run-preserved")).thenReturn(result);

        Captured captured = run(stock(service), "run-daily-basic-job", "--resume-from", "run-preserved");
        var failure = assertInstanceOf(IncompleteCommandException.class, captured.failure());
        assertEquals("daily-basic resume incomplete: " + state + "; run=run-preserved", failure.getMessage());
        assertEquals(3, CliExitStatus.failureCode(failure));
        assertEquals(JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result)
                + System.lineSeparator(), captured.output());
        JsonNode output = json(captured);
        assertEquals("run-preserved", output.path("runId").textValue());
        assertEquals(state, output.path("state").textValue());
        assertEquals(12, output.path("sourceRows").intValue());
        assertEquals(7, output.path("verifiedRows").intValue());
        assertEquals(3, output.path("reusedRows").intValue());
        verify(service).resume("run-preserved");
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"VERIFIED", "VERIFIED_EMPTY"})
    void stockResumeAcceptsBothVerifiedStatesAndPreservesPrintedResult(String state) throws Exception {
        var service = mock(DailyBasicJobService.class);
        when(service.resume("run-complete")).thenReturn(new SyncJobRunner.Result(
                "run-complete", SyncRunState.valueOf(state), 0, 0, null, 0));

        Captured captured = run(stock(service), "run-daily-basic-job", "--resume-from=run-complete");
        assertNull(captured.failure());
        assertEquals(state, json(captured).path("state").textValue());
        verify(service).resume("run-complete");
        verifyNoMoreInteractions(service);
    }

    @Test void errorCodeStillMakesVerifiedStateIncompleteAfterItsOutput() throws Exception {
        var service = mock(DailyBasicJobService.class);
        when(service.resume("run-error")).thenReturn(new SyncJobRunner.Result(
                "run-error", SyncRunState.VERIFIED, 1, 1, "PostVerificationFailure", 0));

        Captured captured = run(stock(service), "run-daily-basic-job", "--resume-from=run-error");
        assertInstanceOf(IncompleteCommandException.class, captured.failure());
        assertEquals(3, CliExitStatus.failureCode(captured.failure()));
        assertEquals("PostVerificationFailure", json(captured).path("errorCode").textValue());
    }

    @Test void stockCommandSemanticValidationRejectsInvalidScopeBeforeCallingOwner() throws Exception {
        var service = mock(DailyBasicJobService.class);
        var runner = stock(service);
        for (String[] args : List.of(
                new String[]{"plan-daily-basic-job"},
                new String[]{"plan-daily-basic-job", "--logical-date=not-a-date"},
                new String[]{"plan-daily-basic-job", "--logical-date=2026-09-29", "--mode=unknown"},
                new String[]{"plan-daily-basic-job", "--resume-from=run-one"},
                new String[]{"run-daily-basic-job", "--resume-from=run-one", "--logical-date=2026-09-29"},
                new String[]{"run-daily-basic-job", "--logical-date=2026-09-29", "--unexpected=value"})) {
            Captured captured = run(runner, args);
            assertNotNull(captured.failure());
            assertEquals(2, CliExitStatus.failureCode(captured.failure()));
            assertEquals("", captured.output());
        }
        verifyNoInteractions(service);
    }

    @Test void genericCatalogPlanningUsesOnlyFrozenDefinitionsAndNeverInvokesExecutionOwners() throws Exception {
        var definition = StockBasicSyncAdapter.definition(true);
        var datasets = new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION));
        var jobs = new SyncJobRegistry(List.of(definition), datasets,
                Map.of(definition.datasetId(), definition.supportedModes()), new SyncJobRegistry.Policies(
                Set.of(definition.ratePolicyRef()), Set.of(definition.slicePolicyRef()), Set.of(definition.verificationPolicyRef())));
        var jobOwner = mock(StockBasicJobService.class);
        var legacy = mock(StockBasicSyncService.class);
        var runner = new CommandLineRunner(new CliCommandRegistry(List.of(
                new CatalogCommands(datasets, jobs),
                new StockCommands(jobOwner, null, null, null, null, null, null, null),
                new LegacyQuestDbCommands(legacy))));

        for (String command : List.of("plan-sync-job", "validate-sync-job")) {
            Captured captured = run(runner, command, "--job=data.stock_basic", "--version=2",
                    "--logical-date=2026-09-29", "--parameters={\"codes\":[\"000001.SZ\"]}");
            assertNull(captured.failure());
            JsonNode output = json(captured);
            assertEquals(command.startsWith("plan") ? "PLANNED" : "VALIDATED", output.path("status").textValue());
            assertFalse(output.path("executed").booleanValue());
            assertFalse(output.path("dataVerified").booleanValue());
            assertEquals("2026-09-29", output.at("/request/logicalDate").textValue());
            assertEquals("000001.SZ", output.at("/request/parameters/codes/0").textValue());
        }
        verifyNoInteractions(jobOwner, legacy);
    }

    @Test void l2ResumeKeepsFrozenPlanDispatchAndPrintsBeforeIncompleteExit() throws Exception {
        var service = mock(L2DailyFeaturesJobService.class);
        var plan = mock(L2DailyFeaturesJobService.Plan.class);
        var from = LocalDate.of(2026, 9, 21);
        var to = LocalDate.of(2026, 9, 22);
        var symbols = List.of("000001.SZ", "600000.SH");
        when(service.plan(from, to, to, SyncJobDefinition.Mode.INGEST, symbols)).thenReturn(plan);
        var result = new SyncJobRunner.Result("l2-resumed", SyncRunState.PARTIAL, 4, 2, "MissingRows", 0);
        when(service.resume(plan, "l2-before")).thenReturn(result);
        var runner = new CommandLineRunner(new CliCommandRegistry(List.of(new L2Commands(null, service, null, null, null))));

        Captured captured = run(runner, "run-l2-daily-features-job", "--from=2026-09-21", "--to=2026-09-22",
                "--logical-date=2026-09-22", "--symbols=000001.SZ,600000.SH", "--mode=ingest", "--resume-from=l2-before");
        var failure = assertInstanceOf(IncompleteCommandException.class, captured.failure());
        assertEquals("D086 sync incomplete: PARTIAL; run=l2-resumed", failure.getMessage());
        assertEquals(3, CliExitStatus.failureCode(failure));
        assertEquals(JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result)
                + System.lineSeparator(), captured.output());
        verify(service).plan(from, to, to, SyncJobDefinition.Mode.INGEST, symbols);
        verify(service).resume(plan, "l2-before");
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void l2CancelPreservesRequestOnlyStatusAndCompactJson(boolean accepted) throws Exception {
        var service = mock(L2DailyFeaturesJobService.class);
        when(service.cancel("l2-run")).thenReturn(accepted);
        var runner = new CommandLineRunner(new CliCommandRegistry(List.of(new L2Commands(null, service, null, null, null))));

        Captured captured = run(runner, "cancel-l2-daily-features-run", "--run=l2-run");
        assertNull(captured.failure());
        var output = json(captured);
        assertEquals(accepted ? "CANCEL_REQUESTED" : "ALREADY_TERMINAL", output.path("status").textValue());
        assertEquals("l2-run", output.path("runId").textValue());
        assertFalse(output.path("executed").booleanValue());
        assertFalse(output.path("dataVerified").booleanValue());
        assertEquals(1, captured.output().lines().count());
        verify(service).cancel("l2-run");
        verifyNoMoreInteractions(service);
    }

    private static CommandLineRunner stock(DailyBasicJobService service) {
        return new CommandLineRunner(new CliCommandRegistry(List.of(
                new StockCommands(null, null, null, service, null, null, null, null))));
    }

    private static Captured run(CommandLineRunner runner, String... args) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var original = System.out;
        Exception failure = null;
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            try { runner.run(new DefaultApplicationArguments(args)); }
            catch (Exception caught) { failure = caught; }
        } finally {
            System.setOut(original);
        }
        return new Captured(bytes.toString(StandardCharsets.UTF_8), failure);
    }

    private static JsonNode json(Captured captured) throws Exception {
        return JobDefinitionJson.mapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(captured.output());
    }

    private record Captured(String output, Exception failure) {}
}
