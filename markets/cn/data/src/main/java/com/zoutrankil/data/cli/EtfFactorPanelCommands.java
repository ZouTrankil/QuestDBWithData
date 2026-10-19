package com.zoutrankil.data.cli;

import com.zoutrankil.data.factor.application.EtfFactorPanelComputation;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** CLI presentation for native ETF computation and observation export. */
@Component
@ConditionalOnNotWebApplication
public final class EtfFactorPanelCommands implements CliCommandFamily {
    private final EtfFactorPanelComputation computation;
    public EtfFactorPanelCommands(JdbcTemplate jdbc){this.computation=new EtfFactorPanelComputation(jdbc);}
    void requireSourceStateUnchanged(com.fasterxml.jackson.databind.JsonNode panel)throws Exception {
        computation.requireSourceStateUnchanged(panel);
    }
    @Override public Set<String> commands(){return Set.of("compute-etf-factor-panel","export-etf-observations");}
    @Override public void execute(String command,Map<String,String> options)throws Exception {
        if(command.equals("export-etf-observations")) {
            if(!options.keySet().containsAll(Set.of("--panel","--output","--computed-at"))
                    || !Set.of("--panel","--output","--computed-at","--factors").containsAll(options.keySet()))
                throw new IllegalArgumentException("Expected --panel PATH --output NEW_PATH --computed-at DATETIME [--factors ID,ID]");
            var request=new EtfFactorPanelComputation.ExportRequest(Path.of(options.get("--panel")),
                    Path.of(options.get("--output")),LocalDateTime.parse(options.get("--computed-at")),
                    options.containsKey("--factors")?List.of(options.get("--factors").split(",",-1)):List.of());
            CliOutput.printJson(computation.exportObservations(request),CliOutput.Profile.PLAIN,false);
            return;
        }
        if(!command.equals("compute-etf-factor-panel") || !options.keySet().equals(Set.of("--history-from","--from","--to","--output")))
            throw new IllegalArgumentException("Expected --history-from DATE --from DATE --to DATE --output PATH");
        var request=new EtfFactorPanelComputation.Request(LocalDate.parse(options.get("--history-from")),
                LocalDate.parse(options.get("--from")),LocalDate.parse(options.get("--to")),Path.of(options.get("--output")));
        CliOutput.printJson(computation.compute(request),CliOutput.Profile.JOB_DEFINITION,true);
    }
}
