package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Reuses exact frozen numerical inputs; partial-source status is deliberately preserved. */
public final class FactorPipelineReuse {
    private FactorPipelineReuse() {}
    @FunctionalInterface public interface SourceCheck { void check(List<Map<String,Object>> sources) throws Exception; }
    public record Reused(String executionId,String observationSha256,String poolSha256) {}
    public static Optional<Reused> copyIfCurrent(Path previous,Path destination,LocalDate date,
            String factors,String pools,String kernelSha,String catalogSha,String aliasSha,
            FactorDefinitionCatalog catalog,SourceCheck sourceCheck) throws Exception {
        byte[] receiptBytes=read(previous.resolve("pipeline.json"),1024*1024);
        var receipt=JobDefinitionJson.mapper().readTree(receiptBytes);
        if(!"java.factor_pipeline.v1".equals(receipt.path("schema").asText())
                || !Set.of("PARTIAL","COMPUTED","COVERAGE_RECORDED").contains(receipt.path("phase").asText())
                || !date.toString().equals(receipt.path("date").asText())
                || !factors.equals(receipt.path("factors").asText()) || !pools.equals(receipt.path("pools").asText()))
            return Optional.empty();
        byte[] reportBytes=read(previous.resolve("compute.json"),4*1024*1024);
        var report=JobDefinitionJson.mapper().readTree(reportBytes);
        var pool=FactorPoolEvidence.read(previous.resolve("pools.json"));
        var raw=FactorObservationEvidence.read(previous.resolve("observations.json"));
        if(!pool.sha256().equals(report.path("poolArtifactSha256").asText())
                || !raw.fileSha256().equals(report.path("evidenceSha256").asText())
                || !raw.inputFingerprint().equals(report.path("inputFingerprint").asText()))return Optional.empty();
        var artifact=JobDefinitionJson.mapper().readTree(pool.bytes());var identity=artifact.path("inputIdentity");
        if(!kernelSha.equals(identity.path("kernelSha256").asText())
                || !catalogSha.equals(identity.path("catalogSha256").asText())
                || !aliasSha.equals(identity.path("aliasRulesSha256").asText())
                || !date.toString().equals(identity.path("from").asText())
                || !date.toString().equals(identity.path("through").asText())
                || !identity.path("sourceState").equals(report.path("sourceState")))return Optional.empty();
        // The provenance label alone is not proof that the selected definitions are unchanged.
        var requested=List.of(factors.split(",",-1));
        if(!JobDefinitionJson.mapper().valueToTree(requested).equals(identity.path("factors"))
                || !JobDefinitionJson.mapper().valueToTree(List.of(pools.split(",",-1))).equals(identity.path("pools")))
            return Optional.empty();
        if(requested.stream().anyMatch(id -> !catalog.require(id).assetType().equals("stock")))return Optional.empty();
        for(var section:artifact.path("sections")) {
            var definition=section.path("definition");String id=definition.path("factorId").asText();
            if(!requested.contains(id) || !JobDefinitionJson.mapper().valueToTree(catalog.require(id)).equals(definition))
                return Optional.empty();
        }
        var sourceState=JobDefinitionJson.mapper().convertValue(identity.path("sourceState"),
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>() {});
        if(sourceState.isEmpty())return Optional.empty();
        sourceCheck.check(sourceState);
        byte[] rawBytes=read(previous.resolve("observations.json"),32*1024*1024);
        if(!FileEvidenceStore.sha256(rawBytes).equals(raw.fileSha256()))return Optional.empty();
        // Recheck after all file reads; a source update invalidates reuse without hiding the partial day.
        sourceCheck.check(sourceState);
        FileEvidenceStore.writeNewDurable(destination.resolve("observations.json"),rawBytes);
        FileEvidenceStore.writeNewDurable(destination.resolve("pools.json"),pool.bytes());
        FileEvidenceStore.writeNewDurable(destination.resolve("compute.json"),reportBytes);
        return Optional.of(new Reused(receipt.path("executionId").asText(),raw.fileSha256(),pool.sha256()));
    }
    private static byte[] read(Path path,int maximum) throws Exception {
        return FileEvidenceStore.readBounded(path,maximum,()->new IllegalArgumentException("Oversized pipeline artifact"));
    }
}
