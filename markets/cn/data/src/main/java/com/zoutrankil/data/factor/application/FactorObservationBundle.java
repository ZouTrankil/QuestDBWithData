package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Validated bounded shards produced by the shared stock/market panel. */
public final class FactorObservationBundle {
    private FactorObservationBundle() {}
    public record Part(String file,int rows,String sha256,String inputFingerprint) {}
    public record Manifest(String schema,boolean sourceComplete,String inputFingerprint,
                           List<String> factors,long rows,List<Part> parts) {}
    public record Verified(Manifest manifest,String sha256,Set<LocalDate> dates) {}
    public static Verified read(Path directory)throws Exception {
        if(Files.isSymbolicLink(directory))throw new IllegalArgumentException("Regular bundle directory required");
        var bytes=readFile(directory.resolve("manifest.json"),1024*1024);
        var tree=JobDefinitionJson.mapper().readTree(bytes);
        if(!tree.path("sourceComplete").isBoolean())throw new IllegalArgumentException("Explicit partial source state required");
        var manifest=JobDefinitionJson.mapper().treeToValue(tree,Manifest.class);
        if(!"java.factor_observation_bundle.v1".equals(manifest.schema()) || manifest.sourceComplete()
                || manifest.inputFingerprint()==null || !manifest.inputFingerprint().matches("[a-f0-9]{64}")
                || manifest.factors()==null || manifest.factors().isEmpty() || manifest.factors().size()>512
                || new HashSet<>(manifest.factors()).size()!=manifest.factors().size()
                || manifest.parts()==null || manifest.parts().isEmpty() || manifest.parts().size()>20
                || manifest.rows()<1 || manifest.rows()>2_000_000)throw new IllegalArgumentException("Invalid bounded factor bundle");
        var catalog=FactorDefinitionCatalog.load();var keys=new HashSet<GlobalObservationCoverage.Key>();
        var sections=new HashSet<String>();var dates=new TreeSet<LocalDate>();long count=0;
        for(int index=0;index<manifest.parts().size();index++) {
            var part=manifest.parts().get(index);
            if(!String.format(Locale.ROOT,"observations-%03d.json",index).equals(part.file()) || part.rows()<1 || part.rows()>100_000)
                throw new IllegalArgumentException("Invalid factor bundle part");
            Path path=directory.resolve(part.file());
            if(Files.isSymbolicLink(path) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Regular part file required");
            var evidence=FactorObservationEvidence.read(path);
            String fingerprint=FileEvidenceStore.sha256((manifest.inputFingerprint()+"\n"+count+"\n"+(count+part.rows())).getBytes(StandardCharsets.UTF_8));
            if(!fingerprint.equals(part.inputFingerprint()) || !fingerprint.equals(evidence.inputFingerprint())
                    || !evidence.fileSha256().equals(part.sha256()) || evidence.rows().size()!=part.rows() || evidence.sourceComplete())
                throw new IllegalArgumentException("Factor bundle part identity mismatch");
            for(var row:evidence.rows()) {
                var definition=catalog.require(row.factorId());
                if(!manifest.factors().contains(row.factorId()) || !definition.enabled() || !definition.publishObservations()
                        || !definition.version().equals(row.definitionVersion()) || !definition.assetType().equals(row.entityType())
                        || !keys.add(GlobalObservationCoverage.key(row)))throw new IllegalArgumentException("Invalid or duplicate factor observation");
                dates.add(row.signalDate());sections.add(row.signalDate()+"/"+row.factorId());
            }
            count+=part.rows();
        }
        if(count!=manifest.rows() || dates.isEmpty() || dates.size()>31 || dates.last().toEpochDay()-dates.first().toEpochDay()>30
                || sections.size()!=dates.size()*manifest.factors().size())throw new IllegalArgumentException("Incomplete factor bundle matrix");
        return new Verified(manifest,FileEvidenceStore.sha256(bytes),Set.copyOf(dates));
    }
    public static byte[] readFile(Path path,int limit)throws Exception {
        if(Files.isSymbolicLink(path) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Regular bundle file required");
        return FileEvidenceStore.readBounded(path,limit,()->new IllegalArgumentException("Oversized factor bundle file"));
    }
}
