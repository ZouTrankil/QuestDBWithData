package com.zoutrankil.data.factor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.application.FactorDefinitionCatalog;
import com.zoutrankil.data.factor.application.FactorObservationEvidence;
import com.zoutrankil.data.factor.application.FactorPipelineReuse;
import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import com.zoutrankil.data.repository.FileEvidenceStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class FactorPipelineReuseTest {
    @TempDir Path root;
    private final LocalDate date = LocalDate.parse("2026-09-30");
    private final String kernel = "a".repeat(64), alias = "b".repeat(64);
    private FactorDefinitionCatalog catalog;

    private Path fixture() throws Exception {
        catalog = FactorDefinitionCatalog.load();
        var definition = catalog.require("reversal_1d");
        Path previous = root.resolve("previous");
        Files.createDirectory(previous);
        String rawFingerprint = "c".repeat(64);
        String rawSha =
                FactorObservationEvidence.writeNew(
                        previous.resolve("observations.json"),
                        List.of(
                                new GlobalFactorObservation(
                                        date,
                                        "stock",
                                        "600001.SH",
                                        definition.factorId(),
                                        definition.version(),
                                        1.0,
                                        date.atTime(19, 0),
                                        "complete",
                                        "GLOBAL",
                                        null)),
                        false,
                        rawFingerprint);
        var sources = List.of(Map.of("id", 1, "table_name", "daily", "table_txn", 1, "wal_txn", 1));
        var identity = new LinkedHashMap<String, Object>();
        identity.put("from", date.toString());
        identity.put("through", date.toString());
        identity.put("factors", List.of("reversal_1d"));
        identity.put("pools", List.of("all_market"));
        identity.put("sourceState", sources);
        identity.put("kernelSha256", kernel);
        identity.put("catalogSha256", catalog.sourceConfigSha256());
        identity.put("aliasRulesSha256", alias);
        String fingerprint =
                FileEvidenceStore.sha256(
                        JobDefinitionJson.canonicalMapper().writeValueAsBytes(identity));
        var values =
                Map.of(
                        "raw",
                        List.of(1.0),
                        "rank",
                        List.of(0.5),
                        "zscore",
                        List.of(0.0),
                        "neutralized",
                        List.of(0.0),
                        "effective",
                        List.of(0.0),
                        "status",
                        List.of("ready"));
        var pool =
                Map.of(
                        "schema",
                        "java.factor_pool_values.v1",
                        "sourceComplete",
                        false,
                        "inputIdentity",
                        identity,
                        "inputFingerprint",
                        fingerprint,
                        "coverage",
                        List.of(
                                Map.of(
                                        "date",
                                        date.toString(),
                                        "pool",
                                        "all_market",
                                        "available",
                                        true,
                                        "members",
                                        List.of(
                                                Map.of(
                                                        "symbol",
                                                        "600001.SH",
                                                        "reasons",
                                                        List.of())))),
                        "sections",
                        List.of(
                                Map.of(
                                        "date",
                                        date.toString(),
                                        "poolId",
                                        "all_market",
                                        "definition",
                                        definition,
                                        "symbols",
                                        List.of("600001.SH"),
                                        "values",
                                        values)));
        byte[] poolBytes = JobDefinitionJson.mapper().writeValueAsBytes(pool);
        Files.write(previous.resolve("pools.json"), poolBytes);
        Files.write(
                previous.resolve("compute.json"),
                JobDefinitionJson.mapper()
                        .writeValueAsBytes(
                                Map.of(
                                        "poolArtifactSha256",
                                        FileEvidenceStore.sha256(poolBytes),
                                        "evidenceSha256",
                                        rawSha,
                                        "inputFingerprint",
                                        rawFingerprint,
                                        "sourceState",
                                        sources,
                                        "requestedDefinitions",
                                        List.of(definition))));
        Files.write(
                previous.resolve("pipeline.json"),
                JobDefinitionJson.mapper()
                        .writeValueAsBytes(
                                Map.of(
                                        "schema",
                                        "java.factor_pipeline.v1",
                                        "executionId",
                                        "previous",
                                        "phase",
                                        "PARTIAL",
                                        "date",
                                        date.toString(),
                                        "factors",
                                        "reversal_1d",
                                        "pools",
                                        "all_market")));
        return previous;
    }

    @Test
    void unchangedPartialInputsReuseBytesAfterTwoSourceChecks() throws Exception {
        Path previous = fixture(), destination = Files.createDirectory(root.resolve("next"));
        var checks = new AtomicInteger();
        var reused =
                FactorPipelineReuse.copyIfCurrent(
                        previous,
                        destination,
                        date,
                        "reversal_1d",
                        "all_market",
                        kernel,
                        catalog.sourceConfigSha256(),
                        alias,
                        catalog,
                        sources -> checks.incrementAndGet());
        assertTrue(reused.isPresent());
        assertEquals(2, checks.get());
        for (String file : List.of("compute.json", "observations.json", "pools.json"))
            assertArrayEquals(
                    Files.readAllBytes(previous.resolve(file)),
                    Files.readAllBytes(destination.resolve(file)));
        assertFalse(
                FactorObservationEvidence.read(destination.resolve("observations.json"))
                        .sourceComplete());
    }

    @Test
    void codeChangeSourceRevisionAndDamagedFileNeverReuse() throws Exception {
        Path previous = fixture(), destination = Files.createDirectory(root.resolve("next"));
        assertTrue(
                FactorPipelineReuse.copyIfCurrent(
                                previous,
                                destination,
                                date,
                                "reversal_1d",
                                "all_market",
                                "d".repeat(64),
                                catalog.sourceConfigSha256(),
                                alias,
                                catalog,
                                s -> fail("Code mismatch must reject before database check"))
                        .isEmpty());
        assertThrows(
                IllegalStateException.class,
                () ->
                        FactorPipelineReuse.copyIfCurrent(
                                previous,
                                destination,
                                date,
                                "reversal_1d",
                                "all_market",
                                kernel,
                                catalog.sourceConfigSha256(),
                                alias,
                                catalog,
                                s -> {
                                    throw new IllegalStateException("source revision");
                                }));
        Files.writeString(previous.resolve("observations.json"), "\n", StandardOpenOption.APPEND);
        assertTrue(
                FactorPipelineReuse.copyIfCurrent(
                                previous,
                                destination,
                                date,
                                "reversal_1d",
                                "all_market",
                                kernel,
                                catalog.sourceConfigSha256(),
                                alias,
                                catalog,
                                s -> fail("Damaged file must reject before database check"))
                        .isEmpty());
        try (var files = Files.list(destination)) {
            assertEquals(0, files.count());
        }
    }
}
