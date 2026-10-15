package com.zoutrankil.data.factor.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Reuse a complete frozen calculation, while retaining its partial-source admission. */
public final class EtfPipelineReuse {
    private EtfPipelineReuse() {}
    @FunctionalInterface public interface SourceCheck { void check(JsonNode panel)throws Exception; }
    public record Reused(String computedAt,String panelSha256,String executionDirectory) {}

    public static String aliasSha256()throws Exception {
        try(var stream=EtfPipelineReuse.class.getResourceAsStream("/factor/stock-code-aliases.tsv")) {
            if(stream==null)throw new IllegalStateException("Missing stock alias rules");
            return FileEvidenceStore.sha256(stream.readAllBytes());
        }
    }

    public static Optional<Reused> copyIfCurrent(Path previous,Path destination,LocalDate date,LocalDate history,
            String kernel,FactorDefinitionCatalog catalog,String aliases,SourceCheck sources)throws Exception {
        var json=JobDefinitionJson.mapper();
        var receipt=json.readTree(read(previous.resolve("pipeline.json"),1024*1024));
        if(!"java.etf_pipeline.v1".equals(receipt.path("schema").asText())
                || !"PARTIAL".equals(receipt.path("phase").asText())
                || !date.toString().equals(receipt.path("date").asText())
                || !history.toString().equals(receipt.path("historyFrom").asText())
                || !kernel.equals(receipt.path("kernelSha256").asText())
                || !catalog.sourceConfigSha256().equals(receipt.path("catalogSha256").asText())
                || !aliases.equals(receipt.path("aliasRulesSha256").asText()))return Optional.empty();
        byte[] panelBytes=read(previous.resolve("panel.json"),64*1024*1024);
        String panelSha=FileEvidenceStore.sha256(panelBytes);
        if(!panelSha.equals(receipt.path("panelSha256").asText()))return Optional.empty();
        var panel=json.readTree(panelBytes);
        if(!"java.etf_shared_panel.v1".equals(panel.path("schema").asText())
                || !kernel.equals(panel.path("kernelSha256").asText())
                || !history.toString().equals(panel.path("historyFrom").asText())
                || !date.toString().equals(panel.path("from").asText())
                || !date.toString().equals(panel.path("through").asText()))return Optional.empty();
        for(String group:List.of("stockSourceState","csiMarketSourceState"))for(var source:panel.path(group)) {
            var nativeVersion=source.path(com.zoutrankil.data.factor.storage.BacktestFactorSource.VERSION_KEY);
            if(!nativeVersion.isTextual()||nativeVersion.asText().isBlank())return Optional.empty();
        }
        var frozen=FactorObservationEvidence.read(previous.resolve("observations.json"));
        if(frozen.sourceComplete() || !frozen.fileSha256().equals(receipt.path("evidenceSha256").asText())
                || frozen.rows().size()!=receipt.path("expectedRows").asInt(-1)
                || frozen.rows().size()!=receipt.path("publication").path("verifiedRows").asLong(-1))return Optional.empty();
        var computed=LocalDateTime.parse(receipt.path("computedAt").asText());
        if(computed.isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))))return Optional.empty();
        if(!EtfPanelObservations.convert(panel,catalog,computed).equals(frozen.rows()))return Optional.empty();
        var factors=new TreeSet<String>();
        for(var definition:catalog.definitions())if(definition.assetType().equals("etf")&&definition.enabled()&&definition.publishObservations())
            factors.add(definition.factorId());
        var expectedFingerprint=FileEvidenceStore.sha256(JobDefinitionJson.canonicalMapper().writeValueAsBytes(Map.of(
                "panelSha256",panelSha,"catalogSha256",catalog.sourceConfigSha256(),
                "emitterSha256",kernel,"computedAt",computed.toString(),"factorIds",factors)));
        if(!expectedFingerprint.equals(frozen.inputFingerprint()))return Optional.empty();
        sources.check(panel);
        byte[] observations=read(previous.resolve("observations.json"),32*1024*1024);
        if(!FileEvidenceStore.sha256(observations).equals(frozen.fileSha256()))return Optional.empty();
        sources.check(panel);
        FileEvidenceStore.writeNewDurable(destination.resolve("panel.json"),panelBytes);
        FileEvidenceStore.writeNewDurable(destination.resolve("observations.json"),observations);
        return Optional.of(new Reused(computed.toString(),panelSha,previous.toString()));
    }
    private static byte[] read(Path file,int limit)throws Exception {
        return FileEvidenceStore.readBounded(file,limit,()->new IllegalArgumentException("ETF reuse artifact exceeds budget"));
    }
}
