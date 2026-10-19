package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import com.zoutrankil.data.factor.engine.MultiPoolFactorPipeline;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Bounded immutable publication parts from one shared computation. Manifest is committed last. */
public final class L2ObservationBundle {
    private L2ObservationBundle() {}
    public record Part(String file,String sha256,long rows,String inputFingerprint) {}
    public record Manifest(String schema,boolean sourceComplete,String inputFingerprint,
            List<String> factorIds,List<LocalDate> dates,long rows,List<Part> parts) {}
    public record Verified(Manifest manifest,String manifestSha256) {}
    /** Validate every part before the publisher can open a write session. */
    public static Verified read(Path directory) throws Exception {
        byte[] bytes=FileEvidenceStore.readBounded(directory.resolve("manifest.json"),1024*1024,
                ()->new IllegalArgumentException("L2 bundle manifest too large"));
        var manifest=JobDefinitionJson.mapper().readValue(bytes,Manifest.class);
        if(!"java.l2_observation_bundle.v1".equals(manifest.schema()) || manifest.sourceComplete()
                || manifest.inputFingerprint()==null || !manifest.inputFingerprint().matches("[0-9a-f]{64}")
                || manifest.rows()<1 || manifest.rows()>2_000_000 || manifest.parts().isEmpty() || manifest.parts().size()>400
                || manifest.factorIds().isEmpty() || manifest.dates().isEmpty() || manifest.dates().size()>31
                || new HashSet<>(manifest.factorIds()).size()!=manifest.factorIds().size()
                || new HashSet<>(manifest.dates()).size()!=manifest.dates().size())
            throw new IllegalArgumentException("Invalid L2 bundle manifest");
        long count=0;var keys=new HashSet<GlobalObservationCoverage.Key>();var sections=new HashSet<String>();
        var catalog=FactorDefinitionCatalog.load();
        for(int index=0;index<manifest.parts().size();index++) {
            var part=manifest.parts().get(index);
            if(!part.file().equals(String.format(Locale.ROOT,"observations-%03d.json",index)))
                throw new IllegalArgumentException("Invalid L2 bundle part name");
            var evidence=FactorObservationEvidence.read(directory.resolve(part.file()));
            String expectedFingerprint=FileEvidenceStore.sha256((manifest.inputFingerprint()+"\n"+index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(!evidence.fileSha256().equals(part.sha256()) || evidence.sourceComplete() || evidence.rows().size()!=part.rows()
                    || !evidence.inputFingerprint().equals(part.inputFingerprint()) || !expectedFingerprint.equals(part.inputFingerprint()))
                throw new IllegalArgumentException("L2 bundle part identity mismatch");
            for(var row:evidence.rows()) {
                var d=catalog.require(row.factorId());
                if(!manifest.factorIds().contains(row.factorId()) || !manifest.dates().contains(row.signalDate())
                        || !d.enabled() || !d.publishObservations() || !d.sourceGroup().equals("l2_microstructure")
                        || !d.version().equals(row.definitionVersion()) || !d.assetType().equals(row.entityType())
                        || !keys.add(GlobalObservationCoverage.key(row)))
                    throw new IllegalArgumentException("Invalid or duplicate L2 bundle observation");
                sections.add(row.signalDate()+"/"+row.factorId());
            }
            count+=evidence.rows().size();
        }
        if(count!=manifest.rows() || sections.size()!=manifest.factorIds().size()*manifest.dates().size())
            throw new IllegalArgumentException("L2 bundle coverage mismatch");
        return new Verified(manifest,FileEvidenceStore.sha256(bytes));
    }
    public static Manifest writeNew(Path directory,List<MultiPoolFactorPipeline.Output> sections,
            List<L2SourceScope.Decision> decisions,List<String> factorIds,FactorDefinitionCatalog catalog,
            LocalDateTime computedAt,String inputFingerprint) throws Exception {
        if(!inputFingerprint.matches("[0-9a-f]{64}") || factorIds.isEmpty()
                || new HashSet<>(factorIds).size()!=factorIds.size())throw new IllegalArgumentException("Unique factors and input fingerprint required");
        var dates=new TreeMap<LocalDate,L2SourceScope.Decision>();
        for(var decision:decisions)if(!decision.publishable() || dates.putIfAbsent(decision.receipt().date(),decision)!=null)
            throw new IllegalArgumentException("Blocked or duplicate L2 date");
        var selected=sections.stream().filter(s->s.poolId().equals("all_market"))
                .sorted(Comparator.comparing(MultiPoolFactorPipeline.Output::date).thenComparing(s->s.definition().factorId())).toList();
        var seen=new HashSet<String>();long total=0;
        for(var section:selected) {
            if(!dates.containsKey(section.date()) || !factorIds.contains(section.definition().factorId())
                    || !seen.add(section.date()+"/"+section.definition().factorId()))
                throw new IllegalArgumentException("Unexpected all_market L2 section");
            // Validate the complete request before creating any export files.
            var rows=L2GlobalObservations.fromPools(List.of(section),List.of(dates.get(section.date())),
                    List.of(section.definition().factorId()),catalog,computedAt);
            total+=rows.size();if(total>2_000_000)throw new IllegalArgumentException("L2 bundle exceeds 2000000 observations");
        }
        if(total==0 || seen.size()!=dates.size()*factorIds.size())throw new IllegalArgumentException("Missing L2 bundle sections");
        Path root=directory.toAbsolutePath().normalize();Files.createDirectories(root.getParent());Files.createDirectory(root);
        var parts=new ArrayList<Part>();var batch=new ArrayList<GlobalFactorObservation>();
        for(var section:selected) {
            var rows=L2GlobalObservations.fromPools(List.of(section),List.of(dates.get(section.date())),
                    List.of(section.definition().factorId()),catalog,computedAt);
            if(!batch.isEmpty() && batch.size()+rows.size()>100_000) { parts.add(writePart(root,parts.size(),batch,inputFingerprint));batch.clear(); }
            batch.addAll(rows);
        }
        if(!batch.isEmpty())parts.add(writePart(root,parts.size(),batch,inputFingerprint));
        var manifest=new Manifest("java.l2_observation_bundle.v1",false,inputFingerprint,List.copyOf(factorIds),
                List.copyOf(dates.keySet()),total,List.copyOf(parts));
        FileEvidenceStore.writeNew(root.resolve("manifest.json"),JobDefinitionJson.mapper().writeValueAsBytes(manifest));
        return manifest;
    }
    private static Part writePart(Path root,int index,List<GlobalFactorObservation> rows,String fingerprint) throws Exception {
        String file=String.format(Locale.ROOT,"observations-%03d.json",index);
        String partFingerprint=FileEvidenceStore.sha256((fingerprint+"\n"+index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String sha=FactorObservationEvidence.writeNew(root.resolve(file),rows,false,partFingerprint);
        return new Part(file,sha,rows.size(),partFingerprint);
    }
}
