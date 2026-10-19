package com.zoutrankil.data.cli;

import com.zoutrankil.data.factor.application.FactorPoolEvidence;
import com.zoutrankil.data.factor.application.FactorIncrementalPlanner;
import com.zoutrankil.data.factor.storage.FactorPoolCoverageStore;
import java.nio.file.Path;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnNotWebApplication
public final class FactorPoolCoverageCommands implements CliCommandFamily {
    @Override public Set<String> commands() { return Set.of("record-factor-pool-coverage","inspect-factor-pool-coverage"); }
    @Override public void execute(String command,Map<String,String> options) throws Exception {
        if(!commands().contains(command) || !options.keySet().equals(Set.of("--artifact","--store")))
            throw new IllegalArgumentException("Expected --artifact PATH --store DIRECTORY");
        Path artifact=Path.of(options.get("--artifact"));var evidence=FactorPoolEvidence.read(artifact);
        var store=new FactorPoolCoverageStore(Path.of(options.get("--store")));
        var output=new LinkedHashMap<String,Object>();
        if(command.equals("record-factor-pool-coverage"))output.put("receipt",store.record(artifact));
        var coverage=store.read(evidence.sections());
        output.put("coverage",coverage);
        output.put("retryPlan",FactorIncrementalPlanner.plan(evidence.sections(),coverage,31));
        output.put("sourceComplete",false);
        output.put("scope","pool computation artifacts; no GLOBAL publication or current-source certification");
        CliOutput.printJson(output,CliOutput.Profile.JOB_DEFINITION,true);
    }
}
