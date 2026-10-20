package com.zoutrankil.data.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.factor.application.FactorDefinitionCatalog;
import com.zoutrankil.data.factor.application.FactorObservationEvidence;
import com.zoutrankil.data.factor.application.L2ObservationBundle;
import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.SyncJobRunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class L2ObservationBundleCommandsTest {
    @TempDir Path root;

    private Map<String, String> setupBundle() throws Exception {
        var date = LocalDate.parse("2026-09-30");
        var catalog = FactorDefinitionCatalog.load();
        var ids = List.of("l2_deal_records_raw", "l2_deal_records_delta3");
        Path bundle = root.resolve("bundle");
        Files.createDirectory(bundle);
        var parts = new ArrayList<L2ObservationBundle.Part>();
        String fingerprint = "a".repeat(64);
        for (int i = 0; i < 2; i++) {
            var row =
                    new GlobalFactorObservation(
                            date,
                            "stock",
                            "600000.SH",
                            ids.get(i),
                            catalog.require(ids.get(i)).version(),
                            1.0,
                            date.atTime(19, 0),
                            "complete",
                            "GLOBAL",
                            null);
            String partFingerprint =
                    FileEvidenceStore.sha256(
                            (fingerprint + "\n" + i).getBytes(StandardCharsets.UTF_8));
            String file = String.format("observations-%03d.json", i);
            String sha =
                    FactorObservationEvidence.writeNew(
                            bundle.resolve(file), List.of(row), false, partFingerprint);
            parts.add(new L2ObservationBundle.Part(file, sha, 1, partFingerprint));
        }
        var manifest =
                new L2ObservationBundle.Manifest(
                        "java.l2_observation_bundle.v1",
                        false,
                        fingerprint,
                        ids,
                        List.of(date),
                        2,
                        parts);
        FileEvidenceStore.writeNew(
                bundle.resolve("manifest.json"),
                JobDefinitionJson.mapper().writeValueAsBytes(manifest));
        return Map.of(
                "--bundle",
                bundle.toString(),
                "--logical-date",
                date.toString(),
                "--root",
                root.resolve("run").toString());
    }

    @Test
    void verifiedPartialPartsContinueAndNeverBecomeFactorReady() throws Exception {
        var options = setupBundle();
        var publisher = mock(FactorObservationCommands.class);
        when(publisher.publishEvidence(any(), any(), any(), isNull(), anyString()))
                .thenAnswer(
                        call ->
                                new SyncJobRunner.Result(
                                        call.getArgument(4), SyncRunState.PARTIAL, 1, 1, "", 0));
        assertThrows(
                IncompleteCommandException.class,
                () ->
                        new L2ObservationBundleCommands(
                                        publisher, root.resolve("ledger").toString())
                                .execute("run-l2-observation-bundle", options));
        verify(publisher, times(2)).publishEvidence(any(), any(), any(), isNull(), anyString());
        var receipt =
                JobDefinitionJson.mapper()
                        .readTree(Files.readAllBytes(root.resolve("run/publication.json")));
        assertEquals("PARTIAL", receipt.path("phase").asText());
        assertEquals(2, receipt.path("verifiedRows").asInt());
        assertFalse(receipt.path("factorReady").asBoolean());
        assertEquals(2, receipt.path("attempts").size());
    }

    @Test
    void corruptLaterPartPreventsAnyWrites() throws Exception {
        var options = setupBundle();
        Files.writeString(root.resolve("bundle/observations-001.json"), "{}");
        var publisher = mock(FactorObservationCommands.class);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new L2ObservationBundleCommands(
                                        publisher, root.resolve("ledger").toString())
                                .execute("run-l2-observation-bundle", options));
        verifyNoInteractions(publisher);
    }
}
