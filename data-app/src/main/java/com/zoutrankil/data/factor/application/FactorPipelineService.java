package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.factor.storage.FactorPoolCoverageStore;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.SyncJobRunner;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Finite in-process computation and publication; partial evidence never satisfies FactorReady. */
public final class FactorPipelineService {
    public record Request(LocalDate date,String factors,String pools,Path root) {
        public Request {
            Objects.requireNonNull(date);Objects.requireNonNull(root);
            if(factors==null || factors.isBlank() || pools==null || pools.isBlank())
                throw new IllegalArgumentException("Explicit factor and pool plan required");
        }
    }
    public record Result(Path receipt,Map<String,Object> state) {}
    public interface Panel {
        void compute(Map<String,String> options) throws Exception;
        void requireSourceStateUnchanged(List<Map<String,Object>> sources) throws Exception;
    }
    @FunctionalInterface public interface Publisher {
        SyncJobRunner.Result publish(Path evidence,LocalDate date,SyncJobDefinition.Mode mode,
                String previous,String runId) throws Exception;
    }
    private final Panel panels;
    private final Publisher publication;
    public FactorPipelineService(FactorPanelComputation computation,FactorObservationPublicationService publication) {
        this(new Panel() {
            public void compute(Map<String,String> options)throws Exception { computation.compute(options); }
            public void requireSourceStateUnchanged(List<Map<String,Object>> sources) { computation.requireSourceStateUnchanged(sources); }
        },publication::publishEvidence);
    }
    public FactorPipelineService(Panel panels,Publisher publication) {
        this.panels=Objects.requireNonNull(panels);this.publication=Objects.requireNonNull(publication);
    }
    public Result run(Request request) throws Exception {
        LocalDate date=request.date();
        Map<String,String> options=Map.of("--factors",request.factors(),"--pools",request.pools());
        if(date.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))throw new IllegalArgumentException("Future factor date");
        Path root=request.root().toAbsolutePath().normalize();Files.createDirectories(root);
        try(var channel=FileChannel.open(root.resolve("pipeline.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lease=channel.tryLock()) {
            if(lease==null)throw new IllegalStateException("Another factor pipeline owns this output root");
            String executionId=date+"-"+UUID.randomUUID();Path run=root.resolve(executionId);Files.createDirectory(run);
            Path receipt=run.resolve("pipeline.json"),raw=run.resolve("observations"),pools=run.resolve("pools.json");
            var state=new LinkedHashMap<String,Object>();state.put("schema","java.factor_pipeline.v2");
            state.put("executionId",executionId);state.put("date",date);state.put("factors",options.get("--factors"));
            state.put("pools",options.get("--pools"));state.put("sourceComplete",false);state.put("factorReady",false);
            state.put("phase","COMPUTING");save(receipt,state);
            try {
                var reuse=tryReuse(root,run,date,options);
                state.put("computationReused",reuse.isPresent());
                if(reuse.isPresent())state.put("reusedExecutionId",reuse.get().executionId());
                else panels.compute(Map.of("--from",date.toString(),"--to",date.toString(),
                        "--output",run.resolve("compute.json").toString(),"--factors",options.get("--factors"),
                        "--bundle-output",raw.toString(),"--pools",options.get("--pools"),"--pool-output",pools.toString()));
                var bundle=com.zoutrankil.data.factor.application.FactorObservationBundle.read(raw);
                if(!bundle.dates().equals(Set.of(date)))throw new IllegalStateException("Pipeline bundle date differs");
                state.put("observationBundleSha256",bundle.sha256());state.put("observationRows",bundle.manifest().rows());
                state.put("phase","COMPUTED");save(receipt,state);
                state.put("coverageReceipt",new FactorPoolCoverageStore(root.resolve("coverage")).record(pools));
                state.put("phase","COVERAGE_RECORDED");save(receipt,state);
                var attempts=new ArrayList<Map<String,Object>>();state.put("attempts",attempts);
                state.put("phase","PUBLISHING");save(receipt,state);long verifiedRows=0;
                for(int index=0;index<bundle.manifest().parts().size();index++) {
                    var part=bundle.manifest().parts().get(index);Path evidence=raw.resolve(part.file());
                    if(!part.sha256().equals(com.zoutrankil.data.factor.application.FactorObservationEvidence.read(evidence).fileSha256()))
                        throw new IllegalStateException("Observation part changed before publication");
                    String runId="factor-observation-"+UUID.randomUUID();
                    var attempt=new LinkedHashMap<String,Object>();attempt.put("part",index);attempt.put("runId",runId);
                    attempt.put("phase","PUBLISHING");attempt.put("sha256",part.sha256());attempts.add(attempt);save(receipt,state);
                    var result=publication.publish(evidence,date,SyncJobDefinition.Mode.INCREMENTAL,null,runId);
                    attempt.put("result",result);attempt.put("phase",result.state().name());save(receipt,state);
                    if(!Set.of(SyncRunState.PARTIAL,SyncRunState.VERIFIED).contains(result.state()) || result.verifiedRows()!=part.rows())
                        throw new IllegalStateException("Factor bundle part did not verify completely: "+runId);
                    verifiedRows+=result.verifiedRows();state.put("verifiedRows",verifiedRows);save(receipt,state);
                }
                if(!bundle.sha256().equals(com.zoutrankil.data.factor.application.FactorObservationBundle.read(raw).sha256()))
                    throw new IllegalStateException("Observation bundle changed during publication");
                state.put("phase","PARTIAL");save(receipt,state);
                return new Result(receipt,Map.copyOf(state));
            } catch(Exception failure) {
                // Preserve the last phase and writer identity; a publication exception is not proof of no writes.
                state.put("interruptedPhase",state.get("phase"));state.put("phase","FAILED_REQUIRES_INSPECTION");
                state.put("failureType",failure.getClass().getName());save(receipt,state);throw failure;
            }
        }
    }
    private static void save(Path path,Map<String,Object> state) throws Exception {
        FileEvidenceStore.replaceDurable(path,JobDefinitionJson.mapper().writeValueAsBytes(state));
    }
    private Optional<com.zoutrankil.data.factor.application.FactorPipelineReuse.Reused> tryReuse(
            Path root,Path run,LocalDate date,Map<String,String> options) throws Exception {
        var catalog=com.zoutrankil.data.factor.application.FactorDefinitionCatalog.load();
        String kernel=com.zoutrankil.data.factor.application.FactorComputationIdentity.sha256(com.zoutrankil.data.factor.application.FactorPanelComputation.class),alias;
        try(var stream=FactorPipelineService.class.getResourceAsStream("/factor/stock-code-aliases.tsv")) {
            if(stream==null)throw new IllegalStateException("Missing stock aliases");alias=FileEvidenceStore.sha256(stream.readAllBytes());
        }
        List<Path> previous;
        try(var children=Files.list(root)) {
            previous=children.filter(p -> !p.equals(run) && p.getFileName().toString().startsWith(date+"-")
                    && Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparingLong(FactorPipelineService::modified).reversed()).limit(32).toList();
        }
        for(var candidate:previous) {
            try {
                if(!Files.isRegularFile(candidate.resolve("observations/manifest.json"),LinkOption.NOFOLLOW_LINKS))continue;
                var reused=com.zoutrankil.data.factor.application.FactorPipelineReuse.copyIfCurrent(candidate,run,date,
                        options.get("--factors"),options.get("--pools"),kernel,catalog.sourceConfigSha256(),alias,catalog,
                        panels::requireSourceStateUnchanged);
                if(reused.isPresent())return reused;
            } catch(NoSuchFileException | IllegalArgumentException | IllegalStateException
                    | com.fasterxml.jackson.core.JsonProcessingException stale) {
                // Missing/damaged artifacts or changed sources require a fresh computation.
            }
        }
        return Optional.empty();
    }
    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch(java.io.IOException missing) { return 0; }
    }
}
