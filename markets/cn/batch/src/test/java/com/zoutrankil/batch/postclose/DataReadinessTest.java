package com.zoutrankil.batch.postclose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zoutrankil.batch.domain.BusinessState;
import com.zoutrankil.batch.domain.CompletionEvidence;
import com.zoutrankil.batch.domain.RunRequest;
import com.zoutrankil.batch.job.StageExecutor;
import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.batch.storage.SqliteLedger;
import com.zoutrankil.batch.support.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

class DataReadinessTest {
    @TempDir Path archive;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);
    private static final Instant AT = Instant.parse("2026-09-28T12:00:00Z");

    @Test
    void dataReadyBlocksUntilEveryCoreSourceHasMatchingVerifiedCertificate() throws Exception {
        var ledger = mock(SqliteLedger.class);
        var root = rootRequest();
        when(ledger.latestSourceCertificates(DAY)).thenReturn(Map.of());
        var stage = PostCloseGraph.STAGES.getFirst();
        var result =
                new DataReadiness(
                                ledger,
                                archive,
                                (request, ignored) ->
                                        new StageExecutor.Result(
                                                BusinessState.BLOCKED, null, "not-used"))
                        .execute(root, stage);
        assertEquals(BusinessState.BLOCKED, result.state());
        assertNull(result.evidence());
        assertTrue(result.reason().contains("daily:missing"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dataReadyEmitsImmutableAggregateCertificateOnlyForDateAndCalendarAlignedSources(boolean v2)
            throws Exception {
        var ledger = mock(SqliteLedger.class);
        var root = rootRequest(v2);
        when(ledger.environmentNamespace()).thenReturn("fixture");
        var certificates = new LinkedHashMap<String, SqliteLedger.SourceCertificateState>();
        for (String dataset : DataReadiness.REQUIRED_SOURCES) {
            byte[] frozenBytes =
                    Json.write(Map.of("dataset", dataset)).getBytes(StandardCharsets.UTF_8);
            String inputFingerprint =
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(frozenBytes));
            var source =
                    sourceRequest(
                            dataset,
                            root.calendarVersion(),
                            inputFingerprint,
                            version(dataset, v2));
            var artifact = archive.resolve("writes").resolve(dataset).resolve("certificate.json");
            Files.createDirectories(artifact.getParent());
            Files.writeString(artifact, "{}");
            var frozen = archive.resolve("sources").resolve("raw").resolve(dataset + ".json");
            Files.createDirectories(frozen.getParent());
            Files.write(frozen, frozenBytes);
            boolean verifiedEmpty = dataset.equals("stk_st_daily");
            var evidence =
                    new CompletionEvidence(
                            1,
                            "java-source:" + dataset,
                            source.instanceId(),
                            "Source:" + dataset,
                            "batch-" + dataset,
                            DAY,
                            DAY,
                            DAY,
                            DAY,
                            DAY,
                            source.definitionVersion(),
                            "source-v1",
                            source.inputFingerprint(),
                            verifiedEmpty ? 0 : 1,
                            verifiedEmpty ? 0 : 1,
                            true,
                            true,
                            true,
                            true,
                            true,
                            verifiedEmpty,
                            AT,
                            BusinessState.valueOf(verifiedEmpty ? "VERIFIED_EMPTY" : "VERIFIED"),
                            artifact.toString(),
                            null);
            Files.writeString(
                    artifact,
                    Json.write(
                            Map.of(
                                    "schemaVersion",
                                    1,
                                    "target",
                                    "jdb_test_fixture_" + dataset + "_" + source.instanceId(),
                                    "sourceArtifact",
                                    frozen.toString(),
                                    "sourceFingerprint",
                                    source.inputFingerprint(),
                                    "definitionVersion",
                                    source.definitionVersion(),
                                    "verifiedRows",
                                    verifiedEmpty ? 0 : 1,
                                    "batches",
                                    List.of("batch-" + dataset))));
            certificates.put(
                    dataset,
                    new SqliteLedger.SourceCertificateState(
                            source.job(),
                            evidence.state(),
                            Json.write(source),
                            Json.write(evidence)));
        }
        when(ledger.latestSourceCertificates(DAY)).thenReturn(certificates);
        var result =
                new DataReadiness(
                                ledger,
                                archive,
                                (request, stage) ->
                                        new StageExecutor.Result(
                                                BusinessState.BLOCKED, null, "not-used"))
                        .execute(root, PostCloseGraph.STAGES.getFirst());
        assertEquals(BusinessState.VERIFIED, result.state());
        assertNotNull(result.evidence());
        assertEquals(PostCloseGraph.STAGES.getFirst().id(), result.evidence().stage());
        assertTrue(result.evidence().matches(root, "DataReady"));
        assertEquals(DataReadiness.REQUIRED_SOURCES.size() - 1, result.evidence().writtenRows());
        assertTrue(Files.isRegularFile(Path.of(result.evidence().artifact())));
        if (v2) {
            var mismatched =
                    new DataReadiness(ledger, archive, (r, st) -> null)
                            .execute(rootRequest(false), PostCloseGraph.STAGES.getFirst());
            assertEquals(BusinessState.BLOCKED, mismatched.state());
            assertTrue(mismatched.reason().contains("certificate-does-not-match-frozen-plan"));
        }
        var revisedCalendar =
                new RunRequest(
                        "post-close-v2",
                        "post_close",
                        DAY,
                        DAY,
                        DAY,
                        "post-close-v1",
                        "0",
                        null,
                        null,
                        "root-input",
                        "calendar-v2",
                        "Asia/Shanghai",
                        AT,
                        AT,
                        root.scopeIdentity());
        var stale =
                new DataReadiness(ledger, archive, (r, s) -> null)
                        .execute(revisedCalendar, PostCloseGraph.STAGES.getFirst());
        assertEquals(BusinessState.BLOCKED, stale.state());
        assertEquals("data-ready-invalid-frozen-plan", stale.reason());
    }

    private RunRequest rootRequest() throws Exception {
        return rootRequest(false);
    }

    private RunRequest rootRequest(boolean v2) throws Exception {
        var sources =
                PostCloseSourcePlan.REQUIRED.stream()
                        .sorted()
                        .map(
                                dataset ->
                                        new PostCloseSourcePlan.Source(
                                                dataset,
                                                version(dataset, v2),
                                                codes(dataset),
                                                "fixture-universe-v1",
                                                "0",
                                                null,
                                                null))
                        .toList();
        byte[] bytes =
                Json.MAPPER.writeValueAsBytes(
                        new PostCloseSourcePlan.Manifest(
                                1, DAY, "calendar-v1", "Asia/Shanghai", sources));
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path file = archive.resolve("post-close-plans").resolve(digest + ".json");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return new RunRequest(
                "post-close",
                "post_close",
                DAY,
                DAY,
                DAY,
                "post-close-v1",
                "0",
                null,
                null,
                digest,
                "calendar-v1",
                "Asia/Shanghai",
                AT,
                AT,
                digest);
    }

    private static String version(String dataset, boolean v2) {
        return v2 && Set.of("etf_adj", "etf_daily", "stk_st_daily", "stk_suspend").contains(dataset)
                ? dataset + "-trade-date-v2"
                : SourceContract.load(dataset).version();
    }

    private static Set<String> codes(String dataset) {
        return SourceContract.load(dataset).isMarketAggregate()
                ? Set.of()
                : dataset.equals("exchange_calendar") ? Set.of("SSE", "SZSE") : Set.of("000001.SZ");
    }

    private static RunRequest sourceRequest(
            String dataset, String calendar, String inputFingerprint, String definitionVersion) {
        return new RunRequest(
                "source-" + dataset,
                "source_" + dataset,
                DAY,
                DAY,
                DAY,
                definitionVersion,
                "0",
                null,
                null,
                inputFingerprint,
                calendar,
                "Asia/Shanghai",
                AT,
                AT,
                RunRequest.hash(
                        dataset,
                        "fixture-universe-v1",
                        String.join("\n", new TreeSet<>(codes(dataset)))));
    }
}
