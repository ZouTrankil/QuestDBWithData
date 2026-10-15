package com.zoutrankil.data.cli;

import com.zoutrankil.data.factor.application.FactorPanelComputation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** CLI presentation for the shared read-only factor computation service. */
@Component
@ConditionalOnNotWebApplication
public final class FactorPanelCommands implements CliCommandFamily {
    private final FactorPanelComputation computation;
    public FactorPanelCommands(JdbcTemplate jdbc) { this.computation=new FactorPanelComputation(jdbc); }
    @Override public Set<String> commands() { return Set.of("compute-factor-panel"); }
    @Override public void execute(String command,Map<String,String> options) throws Exception {
        if(!commands().contains(command))throw new IllegalArgumentException("Unknown factor panel command");
        CliOutput.printJson(computation.compute(options),CliOutput.Profile.JOB_DEFINITION,true);
    }
    void requireSourceStateUnchanged(List<Map<String,Object>> expected) {
        computation.requireSourceStateUnchanged(expected);
    }
}
