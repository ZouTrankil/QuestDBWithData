package com.zoutrankil.data.factor.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Immutable result interchange for bounded publication and restart, with explicit source identity. */
public final class FactorObservationEvidence {
    private FactorObservationEvidence() {}
    public record Snapshot(List<GlobalFactorObservation> rows, boolean sourceComplete,
            String inputFingerprint, String fileSha256) {
        public Snapshot { rows=List.copyOf(rows); }
    }
    public static String writeNew(Path path,List<GlobalFactorObservation> rows,
            boolean sourceComplete,String inputFingerprint) throws Exception {
        requireFingerprint(inputFingerprint);
        if(rows.isEmpty() || rows.size()>100_000) throw new IllegalArgumentException("Bounded nonempty observations required");
        GlobalObservationCoverage.compare(rows,List.of());
        var encoded=rows.stream().map(row -> Arrays.asList(row.signalDate().toString(),row.entityType(),
                row.entityId(),row.factorId(),row.definitionVersion(),row.factorValue(),row.availableAt().toString(),
                row.qualityStatus(),row.scope(),row.l2SourceLineageDigest())).toList();
        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(Map.of("format","java.factor_observations.v1",
                "inputFingerprint",inputFingerprint,"sourceComplete",sourceComplete,"observations",encoded));
        if(bytes.length>32*1024*1024) throw new IllegalArgumentException("Observation evidence exceeds 32 MiB");
        Path absolute=path.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        FileEvidenceStore.writeNew(absolute,bytes);
        return FileEvidenceStore.sha256(bytes);
    }
    public static Snapshot read(Path path) throws Exception {
        byte[] bytes=FileEvidenceStore.readBounded(path,32*1024*1024,
                () -> new IllegalArgumentException("Observation evidence exceeds 32 MiB"));
        var root=JobDefinitionJson.mapper().readTree(bytes);
        if(!"java.factor_observations.v1".equals(root.path("format").asText())
                || !root.path("sourceComplete").isBoolean()) throw new IllegalArgumentException("Unknown observation evidence format");
        String fingerprint=text(root.path("inputFingerprint"));requireFingerprint(fingerprint);
        var encoded=root.path("observations");
        if(!encoded.isArray() || encoded.isEmpty() || encoded.size()>100_000)
            throw new IllegalArgumentException("Bounded nonempty observations required");
        var rows=new ArrayList<GlobalFactorObservation>(encoded.size());
        for(var row:encoded) {
            if(!row.isArray() || row.size()!=10 || (!row.get(5).isNull() && !row.get(5).isNumber()))
                throw new IllegalArgumentException("Malformed observation record");
            rows.add(new GlobalFactorObservation(LocalDate.parse(text(row.get(0))),text(row.get(1)),text(row.get(2)),
                    text(row.get(3)),text(row.get(4)),row.get(5).isNull()?null:row.get(5).doubleValue(),
                    LocalDateTime.parse(text(row.get(6))),text(row.get(7)),text(row.get(8)),
                    row.get(9).isNull()?null:text(row.get(9))));
        }
        GlobalObservationCoverage.compare(rows,List.of());
        return new Snapshot(rows,root.get("sourceComplete").booleanValue(),fingerprint,FileEvidenceStore.sha256(bytes));
    }
    private static String text(JsonNode node) {
        if(!node.isTextual() || node.textValue().isBlank()) throw new IllegalArgumentException("Nonempty observation text required");
        return node.textValue();
    }
    private static void requireFingerprint(String value) {
        if(value==null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Input SHA-256 required");
    }
}
