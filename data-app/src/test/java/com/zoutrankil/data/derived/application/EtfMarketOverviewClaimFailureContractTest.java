package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.port.EtfMarketOverviewPublicationTarget;
import com.zoutrankil.data.derived.storage.EtfMarketOverviewOwnerProcess;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.zoutrankil.data.derived.application.EtfMarketOverviewCacheTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtfMarketOverviewClaimFailureContractTest {
    @TempDir Path artifacts;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void durableClaimSurvivesBeforeIntentAndLauncherFailuresAcrossFreshSessions(boolean beforeIntent) throws Exception {
        Path var = Files.createDirectories(Path.of("var")).toRealPath();
        Path directory = Files.createTempDirectory(var, "d101-claim-contract-");
        try {
            var config = config(artifacts);
            var launcher = mock(EtfMarketOverviewOwnerProcess.ProcessLauncher.class);
            when(launcher.start(anyList(), any())).thenThrow(new IOException("launcher unavailable"));
            var process = new EtfMarketOverviewOwnerProcess(config.pythonExecutable(), config.bridgeScript(), config.timeout(), launcher);
            var gateway = gateway(config, process);
            var preview = preview(config, true, true);
            Path previewPath = artifacts.resolve("original-preview.json");
            byte[] bytes = JobDefinitionJson.mapper().writeValueAsBytes(preview);
            Files.write(previewPath, bytes, StandardOpenOption.CREATE_NEW);
            var expected = gateway.parsePreview(preview, DAY, preview.path("invocation_id").asText(),
                    previewPath, EtfMarketOverviewCacheOwnerGateway.sha(bytes));
            var ledger = new SyncRunLedger(directory.resolve("ledger.sqlite"));
            var request = EtfMarketOverviewDailyCacheJobService.definition().freeze(SyncJobDefinition.Mode.MATERIALIZE,
                    Map.of("source_version", SOURCE, "target_id", TARGET, "bootstrap_from", DAY), DAY, DAY, DAY);
            ledger.createRun("run", null, TARGET, request);
            submitted(ledger, expected);
            var submission = new VerifiedBatchExecutor.Submission(ledger.path(), "run", "slice", 4, UNIT);
            var firstEnvelope = beforeIntent ? withEvidence(expected, "{") : expected;
            var target = mock(EtfMarketOverviewPublicationTarget.class);
            var first = new DefaultEtfMarketOverviewPublicationSession(gateway, gateway, target, firstEnvelope);
            first.submissionRecorded(submission);
            assertThrows(IOException.class, () -> first.send(List.of(firstEnvelope)));
            assertTrue(first.unresolved());
            assertEquals(SyncRunState.SUBMITTED, ledger.get("slice").state());
            assertEquals(4, ledger.get("slice").revision());
            Path claims = directory.resolve("d101-owner-intent-claims");
            List<Path> claimFiles;
            try (var paths = Files.list(claims)) { claimFiles = paths.toList(); }
            assertEquals(1, claimFiles.size());
            byte[] originalClaim = Files.readAllBytes(claimFiles.getFirst());
            var claim = JobDefinitionJson.mapper().readTree(originalClaim);
            Path intent = Path.of(claim.required("intent_path").asText());
            assertEquals(!beforeIntent, Files.exists(intent));
            assertEquals("slice", claim.required("slice_id").asText());
            assertEquals(4, claim.required("revision").asInt());

            var restarted = gateway(config, process);
            var second = new DefaultEtfMarketOverviewPublicationSession(restarted, restarted, target, expected);
            second.submissionRecorded(submission);
            var failure = assertThrows(IllegalStateException.class, () -> second.send(List.of(expected)));
            assertTrue(failure.getMessage().contains("reconcile without resend"));
            if (beforeIntent) assertInstanceOf(FileAlreadyExistsException.class, failure.getCause());
            assertTrue(second.unresolved());
            assertArrayEquals(originalClaim, Files.readAllBytes(claimFiles.getFirst()));
            verify(launcher, times(beforeIntent ? 0 : 1)).start(anyList(), any());
        } finally {
            assertTrue(directory.toRealPath().startsWith(var));
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static EtfMarketOverviewCacheOwnerGateway gateway(EtfMarketOverviewCacheOwnerGateway.Config config,
            EtfMarketOverviewOwnerProcess process) {
        // Only deployment admission is synthetic; real ledger/event authority and claim IO are exercised.
        return new EtfMarketOverviewCacheOwnerGateway(config, process) {
            @Override protected void requireConfigured() {}
        };
    }

    private static EtfMarketOverviewCachePublicationEnvelope withEvidence(
            EtfMarketOverviewCachePublicationEnvelope row, String evidence) {
        return new EtfMarketOverviewCachePublicationEnvelope(row.tradeDate(), row.sourceVersion(), row.cache(),
                row.receipt(), row.sources(), row.targets(), row.sourcesFingerprint(), row.targetsFingerprint(),
                row.targetId(), row.sourceFingerprint(), row.sourceRows(), row.knownSourceDate(), row.previewPath(),
                row.previewSha256(), evidence, row.previewResponse());
    }

    private static void submitted(SyncRunLedger ledger, EtfMarketOverviewCachePublicationEnvelope expected) throws Exception {
        ledger.transition("run", 0, SyncRunState.RUNNING, "{}");
        ledger.createChild("attempt", SyncRunLedger.Kind.ATTEMPT, "run", "run");
        ledger.transition("attempt", 0, SyncRunState.RUNNING, "{}");
        ledger.createChild("slice", SyncRunLedger.Kind.SLICE, "run", "attempt");
        ledger.transition("slice", 0, SyncRunState.RUNNING, "{}");
        String payload = JobDefinitionJson.mapper().writeValueAsString(Map.of("returnedRows", 1,
                "sourceFingerprint", UNIT, "responseEvidence", expected.responseEvidence()));
        ledger.transition("slice", 1, SyncRunState.FETCHED, payload);
        ledger.transition("slice", 2, SyncRunState.VALIDATED, payload);
        ledger.transition("slice", 3, SyncRunState.SUBMITTED, payload);
    }
}
