package com.zoutrankil.data.factor.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.repository.FileEvidenceStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reuses exact frozen numerical inputs; partial-source status is deliberately preserved. */
public final class FactorPipelineReuse {
    private FactorPipelineReuse() {}

    @FunctionalInterface
    public interface SourceCheck {
        void check(List<Map<String, Object>> sources) throws Exception;
    }

    public record Reused(String executionId, String observationSha256, String poolSha256) {}

    public static Optional<Reused> copyIfCurrent(
            Path previous,
            Path destination,
            LocalDate date,
            String factors,
            String pools,
            String kernelSha,
            String catalogSha,
            String aliasSha,
            FactorDefinitionCatalog catalog,
            SourceCheck sourceCheck)
            throws Exception {
        byte[] receiptBytes = read(previous.resolve("pipeline.json"), 1024 * 1024);
        var receipt = JobDefinitionJson.mapper().readTree(receiptBytes);
        boolean bundle = "java.factor_pipeline.v2".equals(receipt.path("schema").asText());
        if (!Set.of("java.factor_pipeline.v1", "java.factor_pipeline.v2")
                        .contains(receipt.path("schema").asText())
                || !Set.of("PARTIAL", "COMPUTED", "COVERAGE_RECORDED")
                        .contains(receipt.path("phase").asText())
                || !date.toString().equals(receipt.path("date").asText())
                || !factors.equals(receipt.path("factors").asText())
                || !pools.equals(receipt.path("pools").asText())) return Optional.empty();
        byte[] reportBytes = read(previous.resolve("compute.json"), 4 * 1024 * 1024);
        var report = JobDefinitionJson.mapper().readTree(reportBytes);
        var pool = FactorPoolEvidence.read(previous.resolve("pools.json"));
        var raw =
                bundle
                        ? null
                        : FactorObservationEvidence.read(previous.resolve("observations.json"));
        var shards = bundle ? FactorObservationBundle.read(previous.resolve("observations")) : null;
        if (!pool.sha256().equals(report.path("poolArtifactSha256").asText())
                || (bundle
                        ? !shards.sha256().equals(receipt.path("observationBundleSha256").asText())
                                || !shards.manifest()
                                        .inputFingerprint()
                                        .equals(report.path("inputFingerprint").asText())
                        : !raw.fileSha256().equals(report.path("evidenceSha256").asText())
                                || !raw.inputFingerprint()
                                        .equals(report.path("inputFingerprint").asText())))
            return Optional.empty();
        var artifact = JobDefinitionJson.mapper().readTree(pool.bytes());
        var identity = artifact.path("inputIdentity");
        if (!kernelSha.equals(identity.path("kernelSha256").asText())
                || !catalogSha.equals(identity.path("catalogSha256").asText())
                || !aliasSha.equals(identity.path("aliasRulesSha256").asText())
                || !date.toString().equals(identity.path("from").asText())
                || !date.toString().equals(identity.path("through").asText())
                || !identity.path("sourceState").equals(report.path("sourceState")))
            return Optional.empty();
        // The provenance label alone is not proof that the selected definitions are unchanged.
        var requested = List.of(factors.split(",", -1));
        var stockRequested =
                requested.stream()
                        .filter(id -> catalog.require(id).assetType().equals("stock"))
                        .toList();
        if (stockRequested.isEmpty()
                || requested.stream()
                        .anyMatch(
                                id -> {
                                    var definition = catalog.require(id);
                                    return !definition.assetType().equals("stock")
                                            && !(definition.assetType().equals("market")
                                                    && definition
                                                            .sourceGroup()
                                                            .equals("market_state"));
                                })) return Optional.empty();
        if (!JobDefinitionJson.mapper().valueToTree(stockRequested).equals(identity.path("factors"))
                || !JobDefinitionJson.mapper()
                        .valueToTree(List.of(pools.split(",", -1)))
                        .equals(identity.path("pools"))) return Optional.empty();
        // Market factors have no stock-pool sections: bind their complete definitions separately.
        if (!JobDefinitionJson.mapper()
                .valueToTree(requested.stream().map(catalog::require).toList())
                .equals(report.path("requestedDefinitions"))) return Optional.empty();
        if (bundle
                && (!shards.manifest().factors().equals(requested)
                        || !shards.dates().equals(Set.of(date)))) return Optional.empty();
        for (var section : artifact.path("sections")) {
            var definition = section.path("definition");
            String id = definition.path("factorId").asText();
            if (!stockRequested.contains(id)
                    || !JobDefinitionJson.mapper()
                            .valueToTree(catalog.require(id))
                            .equals(definition)) return Optional.empty();
        }
        var sourceState =
                JobDefinitionJson.mapper()
                        .convertValue(
                                identity.path("sourceState"),
                                new TypeReference<List<Map<String, Object>>>() {});
        if (sourceState.isEmpty()) return Optional.empty();
        sourceCheck.check(sourceState);
        var copied = new LinkedHashMap<Path, byte[]>();
        String observationSha;
        if (bundle) {
            long retainedBytes = pool.bytes().length + reportBytes.length;
            for (var part : shards.manifest().parts()) {
                byte[] partBytes =
                        FactorObservationBundle.readFile(
                                previous.resolve("observations").resolve(part.file()),
                                32 * 1024 * 1024);
                retainedBytes += partBytes.length;
                if (retainedBytes > 256L * 1024 * 1024) return Optional.empty();
                if (!FileEvidenceStore.sha256(partBytes).equals(part.sha256()))
                    return Optional.empty();
                copied.put(destination.resolve("observations").resolve(part.file()), partBytes);
            }
            byte[] manifestBytes =
                    FactorObservationBundle.readFile(
                            previous.resolve("observations/manifest.json"), 1024 * 1024);
            if (!FileEvidenceStore.sha256(manifestBytes).equals(shards.sha256()))
                return Optional.empty();
            copied.put(destination.resolve("observations/manifest.json"), manifestBytes);
            observationSha = shards.sha256();
        } else {
            byte[] rawBytes = read(previous.resolve("observations.json"), 32 * 1024 * 1024);
            if (!FileEvidenceStore.sha256(rawBytes).equals(raw.fileSha256()))
                return Optional.empty();
            copied.put(destination.resolve("observations.json"), rawBytes);
            observationSha = raw.fileSha256();
        }
        // Check the source again after reading every reusable artifact; commit the manifest last.
        sourceCheck.check(sourceState);
        for (var entry : copied.entrySet()) {
            Files.createDirectories(entry.getKey().getParent());
            FileEvidenceStore.writeNewDurable(entry.getKey(), entry.getValue());
        }
        FileEvidenceStore.writeNewDurable(destination.resolve("pools.json"), pool.bytes());
        FileEvidenceStore.writeNewDurable(destination.resolve("compute.json"), reportBytes);
        return Optional.of(
                new Reused(receipt.path("executionId").asText(), observationSha, pool.sha256()));
    }

    private static byte[] read(Path path, int maximum) throws Exception {
        return FileEvidenceStore.readBounded(
                path, maximum, () -> new IllegalArgumentException("Oversized pipeline artifact"));
    }
}
