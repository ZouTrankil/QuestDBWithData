package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

/** Finite ETF producer and incremental publisher, with durable writer identity for recovery. */
@Component
@ConditionalOnNotWebApplication
public final class EtfFactorPipelineCommands implements CliCommandFamily {
    private final EtfFactorPanelCommands panels;
    private final FactorObservationCommands publication;
    public EtfFactorPipelineCommands(EtfFactorPanelCommands panels,FactorObservationCommands publication) {
        this.panels=panels;this.publication=publication;
    }
    @Override public Set<String> commands(){return Set.of("run-etf-factor-pipeline","resume-etf-factor-pipeline");}
    @Override public void execute(String command,Map<String,String> options)throws Exception {
        if(command.equals("resume-etf-factor-pipeline")) { resume(options);return; }
        if(!commands().contains(command) || !options.keySet().equals(Set.of("--date","--history-from","--root")))
            throw new IllegalArgumentException("Expected --date DATE --history-from DATE --root DIRECTORY");
        var date=LocalDate.parse(options.get("--date"));var history=LocalDate.parse(options.get("--history-from"));
        var now=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        if(history.isAfter(date) || date.isAfter(now.toLocalDate()))throw new IllegalArgumentException("Invalid ETF pipeline dates");
        var root=Path.of(options.get("--root")).toAbsolutePath().normalize();Files.createDirectories(root);
        try(var channel=FileChannel.open(root.resolve("etf-pipeline.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException("ETF pipeline already running");
            var run=root.resolve(date+"-"+UUID.randomUUID());Files.createDirectory(run);
            var receipt=run.resolve("pipeline.json");var panel=run.resolve("panel.json");var evidence=run.resolve("observations.json");
            var state=new LinkedHashMap<String,Object>();state.put("schema","java.etf_pipeline.v1");
            state.put("date",date.toString());state.put("historyFrom",history.toString());state.put("sourceComplete",false);
            state.put("factorReady",false);state.put("phase","COMPUTING");save(receipt,state);
            try {
                panels.execute("compute-etf-factor-panel",Map.of("--history-from",history.toString(),"--from",date.toString(),
                        "--to",date.toString(),"--output",panel.toString()));
                state.put("phase","EXPORTING");save(receipt,state);
                panels.execute("export-etf-observations",Map.of("--panel",panel.toString(),"--output",evidence.toString(),
                        "--computed-at",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString()));
                var frozen=com.zoutrankil.data.factor.application.FactorObservationEvidence.read(evidence);
                state.put("expectedRows",frozen.rows().size());state.put("evidenceSha256",frozen.fileSha256());
                var runId="factor-observation-"+UUID.randomUUID();state.put("publicationRunId",runId);
                state.put("phase","PUBLISHING");save(receipt,state);
                var result=publication.publishEvidence(evidence,date,SyncJobDefinition.Mode.INCREMENTAL,null,runId);
                state.put("publication",result);state.put("phase",result.state().name());save(receipt,state);
                CliOutput.printJson(state,CliOutput.Profile.PLAIN,false);
                if((result.state()!=SyncRunState.PARTIAL && result.state()!=SyncRunState.VERIFIED)
                        || result.verifiedRows()!=frozen.rows().size())
                    throw new IncompleteCommandException("ETF publication stopped: "+result.state()+"; receipt="+receipt);
                throw new IncompleteCommandException("ETF source completeness remains partial; receipt="+receipt);
            } catch(IncompleteCommandException incomplete) {throw incomplete;}
            catch(Exception failure) {
                state.put("interruptedPhase",state.get("phase"));state.put("phase","FAILED_REQUIRES_INSPECTION");
                state.put("failureType",failure.getClass().getName());save(receipt,state);throw failure;
            }
        }
    }
    /** Resume the exact frozen output, without recomputing against newer source data. */
    private void resume(Map<String,String> options)throws Exception {
        if(!options.keySet().equals(Set.of("--run")))throw new IllegalArgumentException("Expected --run DIRECTORY");
        var previous=Path.of(options.get("--run")).toAbsolutePath().normalize();
        var root=Objects.requireNonNull(previous.getParent(),"Pipeline root required");
        try(var channel=FileChannel.open(root.resolve("etf-pipeline.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException("ETF pipeline already running");
            var json=JobDefinitionJson.mapper();
            var old=json.readTree(FileEvidenceStore.readBounded(previous.resolve("pipeline.json"),1024*1024,
                    ()->new IllegalArgumentException("Oversized ETF receipt")));
            if(!"java.etf_pipeline.v1".equals(old.path("schema").asText()))
                throw new IllegalArgumentException("ETF pipeline receipt required");
            String prior=old.path("publicationRunId").asText();
            if(!prior.startsWith("factor-observation-"))throw new IllegalArgumentException("No publication to resume");
            var date=LocalDate.parse(old.path("date").asText());
            var source=previous.resolve("observations.json");
            var frozen=com.zoutrankil.data.factor.application.FactorObservationEvidence.read(source);
            if(!frozen.fileSha256().equals(old.path("evidenceSha256").asText())
                    || frozen.rows().size()!=old.path("expectedRows").asInt(-1)
                    || frozen.rows().stream().anyMatch(row->!date.equals(row.signalDate())))
                throw new IllegalArgumentException("ETF resume evidence differs from receipt");
            var bytes=FileEvidenceStore.readBounded(source,32*1024*1024,()->new IllegalArgumentException("Oversized ETF evidence"));
            if(!frozen.fileSha256().equals(FileEvidenceStore.sha256(bytes)))
                throw new IllegalArgumentException("ETF evidence changed during resume");
            publication.requireResumableEvidence(source,date,prior);
            var run=root.resolve(date+"-"+UUID.randomUUID());Files.createDirectory(run);
            var evidence=run.resolve("observations.json");FileEvidenceStore.writeNewDurable(evidence,bytes);
            var receipt=run.resolve("pipeline.json");var state=new LinkedHashMap<String,Object>();
            state.put("schema","java.etf_pipeline.v1");state.put("date",date.toString());
            state.put("historyFrom",old.path("historyFrom").asText());state.put("resumedFrom",previous.toString());
            state.put("priorPublicationRunId",prior);
            state.put("sourceComplete",false);state.put("factorReady",false);
            state.put("expectedRows",frozen.rows().size());state.put("evidenceSha256",frozen.fileSha256());
            String runId="factor-observation-"+UUID.randomUUID();state.put("publicationRunId",runId);
            state.put("phase","PUBLISHING");save(receipt,state);
            try {
                var result=publication.publishEvidence(evidence,date,SyncJobDefinition.Mode.INCREMENTAL,prior,runId);
                state.put("publication",result);state.put("phase",result.state().name());save(receipt,state);
                CliOutput.printJson(state,CliOutput.Profile.PLAIN,false);
                if((result.state()!=SyncRunState.PARTIAL && result.state()!=SyncRunState.VERIFIED)
                        || result.verifiedRows()!=frozen.rows().size())
                    throw new IncompleteCommandException("ETF resumed publication stopped: "+result.state()
                            +"; verifiedRows="+result.verifiedRows()+"; expectedRows="+frozen.rows().size()+"; receipt="+receipt);
                throw new IncompleteCommandException("ETF resumed publication state="+result.state()
                        +", verifiedRows="+result.verifiedRows()+"; source completeness remains partial; receipt="+receipt);
            } catch(IncompleteCommandException incomplete) {throw incomplete;}
            catch(Exception failure) {
                state.put("interruptedPhase",state.get("phase"));state.put("phase","FAILED_REQUIRES_INSPECTION");
                state.put("failureType",failure.getClass().getName());save(receipt,state);throw failure;
            }
        }
    }
    private static void save(Path path,Map<String,Object> state)throws Exception {
        FileEvidenceStore.replaceDurable(path,JobDefinitionJson.mapper().writeValueAsBytes(state));
    }
}
