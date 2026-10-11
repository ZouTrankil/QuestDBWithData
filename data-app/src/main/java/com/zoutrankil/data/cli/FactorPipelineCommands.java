package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.factor.storage.FactorPoolCoverageStore;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

/** Finite native pipeline. Its PARTIAL receipt must not satisfy FactorReady. */
@Component
@ConditionalOnNotWebApplication
public final class FactorPipelineCommands implements CliCommandFamily {
    private final FactorPanelCommands panels;
    private final FactorObservationCommands publication;
    public FactorPipelineCommands(FactorPanelCommands panels,FactorObservationCommands publication) {
        this.panels=panels;this.publication=publication;
    }
    @Override public Set<String> commands() { return Set.of("run-factor-pipeline"); }
    @Override public void execute(String command,Map<String,String> options) throws Exception {
        if(!commands().contains(command) || !options.keySet().equals(Set.of("--date","--factors","--pools","--root")))
            throw new IllegalArgumentException("Expected --date DATE --factors ID,ID --pools ID,ID --root DIRECTORY");
        LocalDate date=LocalDate.parse(options.get("--date"));
        if(date.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))throw new IllegalArgumentException("Future factor date");
        Path root=Path.of(options.get("--root")).toAbsolutePath().normalize();Files.createDirectories(root);
        try(var channel=FileChannel.open(root.resolve("pipeline.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lease=channel.tryLock()) {
            if(lease==null)throw new IllegalStateException("Another factor pipeline owns this output root");
            String executionId=date+"-"+UUID.randomUUID();Path run=root.resolve(executionId);Files.createDirectory(run);
            Path receipt=run.resolve("pipeline.json"),raw=run.resolve("observations.json"),pools=run.resolve("pools.json");
            var state=new LinkedHashMap<String,Object>();state.put("schema","java.factor_pipeline.v1");
            state.put("executionId",executionId);state.put("date",date);state.put("factors",options.get("--factors"));
            state.put("pools",options.get("--pools"));state.put("sourceComplete",false);state.put("factorReady",false);
            state.put("phase","COMPUTING");save(receipt,state);
            try {
                var reuse=tryReuse(root,run,date,options);
                state.put("computationReused",reuse.isPresent());
                if(reuse.isPresent())state.put("reusedExecutionId",reuse.get().executionId());
                else panels.execute("compute-factor-panel",Map.of("--from",date.toString(),"--to",date.toString(),
                        "--output",run.resolve("compute.json").toString(),"--factors",options.get("--factors"),
                        "--evidence-output",raw.toString(),"--pools",options.get("--pools"),"--pool-output",pools.toString()));
                state.put("observationEvidenceSha256",com.zoutrankil.data.factor.application.FactorObservationEvidence.read(raw).fileSha256());
                state.put("phase","COMPUTED");save(receipt,state);
                state.put("coverageReceipt",new FactorPoolCoverageStore(root.resolve("coverage")).record(pools));
                state.put("phase","COVERAGE_RECORDED");save(receipt,state);
                String runId="factor-observation-"+UUID.randomUUID();state.put("publicationRunId",runId);
                state.put("phase","PUBLISHING");save(receipt,state);
                var result=publication.publishEvidence(raw,date,SyncJobDefinition.Mode.INCREMENTAL,null,runId);
                state.put("publication",result);state.put("phase",result.state().name());save(receipt,state);
                CliOutput.printJson(state,CliOutput.Profile.JOB_DEFINITION,true);
                if(result.state()!=SyncRunState.VERIFIED && result.state()!=SyncRunState.VERIFIED_EMPTY)
                    throw new IncompleteCommandException("Factor pipeline incomplete: "+result.state()+"; receipt="+receipt);
            } catch(IncompleteCommandException incomplete) { throw incomplete; }
            catch(Exception failure) {
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
        String kernel=com.zoutrankil.data.factor.application.FactorComputationIdentity.sha256(FactorPanelCommands.class),alias;
        try(var stream=FactorPipelineCommands.class.getResourceAsStream("/factor/stock-code-aliases.tsv")) {
            if(stream==null)throw new IllegalStateException("Missing stock aliases");alias=FileEvidenceStore.sha256(stream.readAllBytes());
        }
        List<Path> previous;
        try(var children=Files.list(root)) {
            previous=children.filter(p -> !p.equals(run) && p.getFileName().toString().startsWith(date+"-")
                    && Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparingLong(FactorPipelineCommands::modified).reversed()).limit(32).toList();
        }
        for(var candidate:previous) {
            try {
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
