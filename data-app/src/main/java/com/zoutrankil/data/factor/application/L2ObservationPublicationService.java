package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Sequential native publication of frozen L2 parts; every rerun rechecks physical rows. */
public final class L2ObservationPublicationService {
    public record Request(Path bundle,LocalDate logicalDate,Path root) {
        public Request { Objects.requireNonNull(bundle);Objects.requireNonNull(logicalDate);Objects.requireNonNull(root); }
    }
    public record Result(Path receipt,Map<String,Object> state) {}
    @FunctionalInterface public interface Publisher {
        com.zoutrankil.data.service.SyncJobRunner.Result publishEvidence(Path evidence,LocalDate date,
                SyncJobDefinition.Mode mode,String previous,String runId)throws Exception;
    }
    private final Publisher publication;
    private final Path ledger;
    public L2ObservationPublicationService(FactorObservationPublicationService publication,Path ledger) {
        this(publication::publishEvidence,ledger);
    }
    public L2ObservationPublicationService(Publisher publication,Path ledger) {
        this.publication=Objects.requireNonNull(publication);this.ledger=Objects.requireNonNull(ledger);
    }
    public static Map<String,Object> inspect(Path bundle)throws Exception {
        var verified=L2ObservationBundle.read(bundle);
        return Map.of("manifestSha256",verified.manifestSha256(),"rows",verified.manifest().rows(),
                "parts",verified.manifest().parts().size(),"factors",verified.manifest().factorIds().size(),
                "sourceComplete",false,"readOnly",true);
    }
    public Result run(Request request)throws Exception {
        var date=request.logicalDate();
        Path bundle=request.bundle().toAbsolutePath().normalize();
        var verified=L2ObservationBundle.read(bundle);
        if(verified.manifest().dates().stream().anyMatch(d->d.isAfter(date)))
            throw new IllegalArgumentException("L2 observations exceed logical date");
        Path root=request.root().toAbsolutePath().normalize();Files.createDirectories(root);
        try(var channel=FileChannel.open(root.resolve("bundle.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException("L2 bundle publisher already running");
            Path receipt=root.resolve("publication.json");
            var attempts=new ArrayList<Map<String,Object>>();
            if(Files.exists(receipt)) {
                var prior=JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(receipt,16*1024*1024,
                        ()->new IllegalArgumentException("L2 publication receipt too large")));
                if(!verified.manifestSha256().equals(prior.path("manifestSha256").asText())
                        || !date.toString().equals(prior.path("logicalDate").asText()))
                    throw new IllegalArgumentException("Output root belongs to another L2 publication");
                for(var attempt:prior.path("attempts")) {
                    @SuppressWarnings("unchecked") var saved=(Map<String,Object>)JobDefinitionJson.mapper().convertValue(attempt,Map.class);
                    if("PUBLISHING".equals(saved.get("phase"))) {
                        var state=SyncRunLedger.openReadOnly(ledger).get((String)saved.get("runId")).state();
                        if(!state.terminal())throw new IllegalStateException("Prior L2 writer requires reconciliation: "+saved.get("runId"));
                        saved.put("phase",state.name());
                    }
                    attempts.add(saved);
                }
            }
            var state=new LinkedHashMap<String,Object>();state.put("schema","java.l2_bundle_publication.v1");
            state.put("manifestSha256",verified.manifestSha256());state.put("logicalDate",date);
            state.put("sourceComplete",false);state.put("factorReady",false);state.put("attempts",attempts);
            state.put("phase","PUBLISHING");long verifiedRows=0;
            try {
                for(int i=0;i<verified.manifest().parts().size();i++) {
                    var part=verified.manifest().parts().get(i);Path evidence=bundle.resolve(part.file());
                    if(!part.sha256().equals(FactorObservationEvidence.read(evidence).fileSha256()))
                        throw new IllegalStateException("L2 evidence part changed before publication");
                    var attempt=new LinkedHashMap<String,Object>();attempt.put("part",i);attempt.put("sha256",part.sha256());
                    String runId="factor-observation-"+UUID.randomUUID();attempt.put("runId",runId);
                    attempt.put("phase","PUBLISHING");attempts.add(attempt);save(receipt,state);
                    var result=publication.publishEvidence(evidence,date,SyncJobDefinition.Mode.INCREMENTAL,null,runId);
                    attempt.put("phase",result.state().name());attempt.put("result",result);save(receipt,state);
                    if(!Set.of(SyncRunState.PARTIAL,SyncRunState.VERIFIED).contains(result.state()) || result.verifiedRows()!=part.rows())
                        throw new IllegalStateException("L2 bundle part did not verify completely: "+runId);
                    verifiedRows+=result.verifiedRows();
                }
                if(verifiedRows!=verified.manifest().rows()
                        || !verified.manifestSha256().equals(L2ObservationBundle.read(bundle).manifestSha256()))
                    throw new IllegalStateException("L2 bundle changed during publication or row count differs");
                state.put("verifiedRows",verifiedRows);state.put("phase","PARTIAL");save(receipt,state);
            } catch(Exception failure) {
                state.put("phase","FAILED_REQUIRES_INSPECTION");state.put("failureType",failure.getClass().getName());
                save(receipt,state);throw failure;
            }
            return new Result(receipt,Collections.unmodifiableMap(state));
        }
    }
    private static void save(Path path,Map<String,Object> state) throws Exception {
        FileEvidenceStore.replaceDurable(path,JobDefinitionJson.mapper().writeValueAsBytes(state));
    }
}
