package com.zoutrankil.data.cli;

import com.zoutrankil.data.factor.application.*;
import com.zoutrankil.data.factor.storage.*;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;

@Component
@ConditionalOnNotWebApplication
public final class PostCloseFactorMonitorCommands implements CliCommandFamily {
    private final PostCloseFactorMonitorService service;
    public PostCloseFactorMonitorCommands(org.springframework.jdbc.core.JdbcTemplate jdbc,
            @org.springframework.context.annotation.Lazy io.questdb.client.QuestDB questdb,
            @org.springframework.beans.factory.annotation.Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) { this(jdbc,questdb,ledger,com.zoutrankil.market.cn.time.CnMarketTime.system()); }
    @org.springframework.beans.factory.annotation.Autowired
    public PostCloseFactorMonitorCommands(org.springframework.jdbc.core.JdbcTemplate jdbc,
            @org.springframework.context.annotation.Lazy io.questdb.client.QuestDB questdb,
            @org.springframework.beans.factory.annotation.Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger, com.zoutrankil.data.domain.temporal.MarketTime marketTime) {
        var observationPublication=new FactorObservationPublicationService(jdbc,questdb,Path.of(ledger));
        service=new PostCloseFactorMonitorService(new FactorPipelineService(new FactorPanelComputation(jdbc,marketTime),observationPublication,marketTime),
                new DailyMetricPipelineService(new DailyMetricComputation(jdbc,marketTime),new DailyMetricPublicationService(jdbc,questdb,Path.of(ledger),marketTime)),
                new QuestDbDailyMetricPlan(jdbc),new QuestDbDailyMetricFreshness(jdbc),new QuestDbDailyMetrics(jdbc));
    }
    @Override public Set<String> commands(){return Set.of("run-post-close-factor-monitor");}
    @Override public void execute(String command,Map<String,String> options)throws Exception {
        var required=Set.of("--as-of","--history-from","--factors","--pools","--label","--root");
        if(!commands().contains(command)||!options.keySet().equals(required))
            throw new IllegalArgumentException("Expected --as-of DATE --history-from DATE --factors ID,ID --pools ID,ID --label DEFINITION --root DIRECTORY");
        var result=service.run(new PostCloseFactorMonitorService.Request(LocalDate.parse(options.get("--as-of")),
                LocalDate.parse(options.get("--history-from")),options.get("--factors"),options.get("--pools"),
                options.get("--label"),Path.of(options.get("--root"))));
        CliOutput.printJson(result.state(),CliOutput.Profile.JOB_DEFINITION,true);
        throw new IncompleteCommandException("Native metric monitor physically verified; inspect coverage and page migration: "+result.receipt());
    }
}
