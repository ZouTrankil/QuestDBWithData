package com.zoutrankil.data.cli;

import com.zoutrankil.data.financial.application.FinancialGrowthRepairJob;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.domain.SyncRunState;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnNotWebApplication
public final class FinancialGrowthRepairCommands implements CliCommandFamily {
    private final JdbcTemplate jdbc;private final Path ledger;
    public FinancialGrowthRepairCommands(JdbcTemplate jdbc,@Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        this.jdbc=jdbc;this.ledger=Path.of(ledger);
    }
    @Override public Set<String> commands(){return Set.of("plan-financial-growth-repair","run-financial-growth-repair");}
    @Override public void execute(String command,Map<String,String> options)throws Exception {
        if(!commands().contains(command) || !options.keySet().equals(Set.of("--source","--logical-date","--evidence-root")))
            throw new IllegalArgumentException("Expected --source CAPTURED_VIP_JSON --logical-date DATE --evidence-root NEW_DIRECTORY");
        var report=FinancialGrowthRepairJob.execute(jdbc,ledger,Path.of(options.get("--source")),Path.of(options.get("--evidence-root")),
                LocalDate.parse(options.get("--logical-date")),command.equals("run-financial-growth-repair"));
        CliOutput.printJson(report,CliOutput.Profile.JOB_DEFINITION,true);
        if(report.get("result") instanceof SyncJobRunner.Result result && result.state()!=SyncRunState.VERIFIED)
            throw new IncompleteCommandException("Financial growth repair: "+result.state()+"; run="+result.runId());
    }
}
