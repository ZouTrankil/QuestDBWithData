package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.factor.application.*;
import com.zoutrankil.data.repository.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

/** Sequential publication of one frozen shared computation; every rerun rechecks physical rows. */
@Component
@ConditionalOnNotWebApplication
public final class L2ObservationBundleCommands implements CliCommandFamily {
    private final FactorObservationCommands publication;
    private final Path ledger;
    public L2ObservationBundleCommands(FactorObservationCommands publication,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        this.publication=publication;this.ledger=Path.of(ledger);
    }
    @Override public Set<String> commands() { return Set.of("run-l2-observation-bundle","inspect-l2-observation-bundle"); }
    @Override public void execute(String command,Map<String,String> options) throws Exception {
        if(command.equals("inspect-l2-observation-bundle")) {
            if(!options.keySet().equals(Set.of("--bundle")))throw new IllegalArgumentException("Expected --bundle DIRECTORY");
            var verified=L2ObservationBundle.read(Path.of(options.get("--bundle")));
            CliOutput.printJson(Map.of("manifestSha256",verified.manifestSha256(),"rows",verified.manifest().rows(),
                    "parts",verified.manifest().parts().size(),"factors",verified.manifest().factorIds().size(),
                    "sourceComplete",false,"readOnly",true),CliOutput.Profile.JOB_DEFINITION,true);
            return;
        }
        if(!commands().contains(command) || !options.keySet().equals(Set.of("--bundle","--logical-date","--root")))
            throw new IllegalArgumentException("Expected --bundle DIRECTORY --logical-date DATE --root DIRECTORY");
        var date=LocalDate.parse(options.get("--logical-date"));
        Path bundle=Path.of(options.get("--bundle")).toAbsolutePath().normalize();
        var verified=L2ObservationBundle.read(bundle);
        if(verified.manifest().dates().stream().anyMatch(d->d.isAfter(date)))
            throw new IllegalArgumentException("L2 observations exceed logical date");
        Path root=Path.of(options.get("--root")).toAbsolutePath().normalize();Files.createDirectories(root);
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
                state.put("verifiedRows",verifiedRows);state.put("phase","PARTIAL");save(receipt,state);
            } catch(Exception failure) {
                state.put("phase","FAILED_REQUIRES_INSPECTION");state.put("failureType",failure.getClass().getName());
                save(receipt,state);throw failure;
            }
            CliOutput.printJson(state,CliOutput.Profile.JOB_DEFINITION,true);
            throw new IncompleteCommandException("L2 bundle rows verified; source completeness remains partial: "+receipt);
        }
    }
    private static void save(Path path,Map<String,Object> state) throws Exception {
        FileEvidenceStore.replaceDurable(path,JobDefinitionJson.mapper().writeValueAsBytes(state));
    }
}
