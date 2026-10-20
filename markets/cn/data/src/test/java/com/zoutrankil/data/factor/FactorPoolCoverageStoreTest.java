package com.zoutrankil.data.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.application.FactorIncrementalPlanner;
import com.zoutrankil.data.factor.application.FactorPoolEvidence;
import com.zoutrankil.data.factor.storage.FactorPoolCoverageStore;
import com.zoutrankil.data.repository.FileEvidenceStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

class FactorPoolCoverageStoreTest {
    @TempDir Path root;

    private Path fixture() throws Exception {
        var json =
                JobDefinitionJson.mapper()
                        .readTree(
                                """
                {"schema":"java.factor_pool_values.v1","sourceComplete":false,
                 "inputIdentity":{"from":"2026-09-30","through":"2026-09-30","pools":["all_market"],"factors":["f"]},
                 "coverage":[{"date":"2026-09-30","pool":"all_market","available":true,"members":[{"symbol":"600001.SH","reasons":[]}]}],
                 "sections":[{"date":"2026-09-30","poolId":"all_market","definition":{"factorId":"f","version":"v1","lookbackPeriods":20},
                 "symbols":["600001.SH"],"values":{"raw":[1],"rank":[null],"zscore":[null],"neutralized":[null],"effective":[null],"status":["blocked_insufficient_cross_section"]}}]}
                """);
        ((ObjectNode) json)
                .put(
                        "inputFingerprint",
                        FileEvidenceStore.sha256(
                                JobDefinitionJson.canonicalMapper()
                                        .writeValueAsBytes(
                                                JobDefinitionJson.mapper()
                                                        .convertValue(
                                                                json.get("inputIdentity"),
                                                                Map.class))));
        Path path = root.resolve("input.json");
        Files.write(path, JobDefinitionJson.mapper().writeValueAsBytes(json));
        return path;
    }

    @Test
    void restartRechecksObjectAndKeepsPartialDateRetryable() throws Exception {
        Path path = fixture(), directory = root.resolve("coverage");
        var evidence = FactorPoolEvidence.read(path);
        var first = new FactorPoolCoverageStore(directory);
        assertEquals(
                "missing",
                FactorIncrementalPlanner.plan(
                                evidence.sections(), first.read(evidence.sections()), 31)
                        .sections()
                        .getFirst()
                        .reason());
        var receipt = first.record(path);
        assertEquals(1, receipt.sections());
        var reopened = new FactorPoolCoverageStore(directory);
        var observed = reopened.read(evidence.sections());
        assertTrue(observed.getFirst().physicalComplete());
        assertFalse(observed.getFirst().sourceComplete());
        assertEquals(
                "partial_source",
                FactorIncrementalPlanner.plan(evidence.sections(), observed, 31)
                        .sections()
                        .getFirst()
                        .reason());
        Path object = directory.resolve("objects").resolve(receipt.fileSha256() + ".json");
        Files.writeString(object, "\n", StandardOpenOption.APPEND);
        assertEquals(
                "physical_gap",
                FactorIncrementalPlanner.plan(
                                evidence.sections(), reopened.read(evidence.sections()), 31)
                        .sections()
                        .getFirst()
                        .reason());
        Files.writeString(object, "invalid-json");
        assertFalse(reopened.read(evidence.sections()).getFirst().physicalComplete());
        Files.delete(object);
        assertFalse(reopened.read(evidence.sections()).getFirst().physicalComplete());
        reopened.record(path);
        assertTrue(reopened.read(evidence.sections()).getFirst().physicalComplete());
    }

    @Test
    void rejectMissingSectionsMisalignmentAndUncertifiedCompletion() throws Exception {
        Path path = fixture();
        String text = Files.readString(path);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FactorPoolEvidence.decode(
                                text.replace("\"raw\":[1]", "\"raw\":[]")
                                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FactorPoolEvidence.decode(
                                text.replace("\"sourceComplete\":false", "\"sourceComplete\":true")
                                        .getBytes(StandardCharsets.UTF_8)));
        var json = (ObjectNode) JobDefinitionJson.mapper().readTree(text);
        json.putArray("sections");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FactorPoolEvidence.decode(
                                JobDefinitionJson.mapper().writeValueAsBytes(json)));
        var tampered = (ObjectNode) JobDefinitionJson.mapper().readTree(text);
        tampered.put("inputFingerprint", "0".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FactorPoolEvidence.decode(
                                JobDefinitionJson.mapper().writeValueAsBytes(tampered)));
    }
}
