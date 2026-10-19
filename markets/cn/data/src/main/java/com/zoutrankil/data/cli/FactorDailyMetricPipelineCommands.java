package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.application.DailyMetricComputation;
import com.zoutrankil.data.factor.application.DailyMetricPublicationService;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

/** Durable composition of daily evaluation and guarded incremental publication. */
@Component
@ConditionalOnNotWebApplication
public final class FactorDailyMetricPipelineCommands implements CliCommandFamily {
    private final DailyMetricComputation computation;
    private final DailyMetricPublicationService publication;
    private final com.zoutrankil.data.factor.storage.QuestDbDailyMetricPlan planner;
    private final com.zoutrankil.data.factor.storage.QuestDbDailyMetricFreshness freshness;
    public FactorDailyMetricPipelineCommands(JdbcTemplate jdbc,@Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) { this(jdbc,questdb,ledgerPath,com.zoutrankil.market.cn.time.CnMarketTime.system()); }
    @org.springframework.beans.factory.annotation.Autowired
    public FactorDailyMetricPipelineCommands(JdbcTemplate jdbc,@Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath, com.zoutrankil.data.domain.temporal.MarketTime marketTime) {
        this.computation=new DailyMetricComputation(jdbc,marketTime);
        this.planner=new com.zoutrankil.data.factor.storage.QuestDbDailyMetricPlan(jdbc);
        this.freshness=new com.zoutrankil.data.factor.storage.QuestDbDailyMetricFreshness(jdbc);
        this.publication=new DailyMetricPublicationService(jdbc,questdb,Path.of(ledgerPath),marketTime);
    }
    @Override public Set<String> commands(){return Set.of("run-factor-daily-metric-pipeline");}
    @Override public void execute(String command,Map<String,String> options)throws Exception {
        var required=Set.of("--as-of","--observations","--pools","--label","--root");
        var allowed=new HashSet<>(required);allowed.add("--date");allowed.add("--freshness-plan");
        if(!commands().contains(command) || !options.keySet().containsAll(required)||!allowed.containsAll(options.keySet()))
            throw new IllegalArgumentException("Expected [--date DATE] --as-of DATE --observations PATH,PATH --pools PATH --label DEFINITION --root DIRECTORY [--freshness-plan PATH]");
        com.zoutrankil.data.factor.storage.QuestDbDailyMetricFreshness.Request freshnessPlan=null;
        if(options.containsKey("--freshness-plan")) {
            freshnessPlan=JobDefinitionJson.mapper().readValue(FileEvidenceStore.readBounded(Path.of(options.get("--freshness-plan")),
                    4*1024*1024,()->new IllegalArgumentException("Freshness plan exceeds limit")),
                    com.zoutrankil.data.factor.storage.QuestDbDailyMetricFreshness.Request.class);
            var signal=options.containsKey("--date")?LocalDate.parse(options.get("--date")):
                    planner.plan(null,LocalDate.parse(options.get("--as-of"))).pairs().getLast().signal();
            if(!freshnessPlan.asOf().equals(LocalDate.parse(options.get("--as-of")))
                    ||!freshnessPlan.labelDefinition().equals(options.get("--label"))
                    ||freshnessPlan.signalDate()!=null&&!freshnessPlan.signalDate().equals(signal)
                    ||new HashSet<>(freshnessPlan.expected()).size()!=freshnessPlan.expected().size())
                throw new IllegalArgumentException("Freshness plan must match run dates/label and contain unique expected keys");
        }
        var observations=new ArrayList<Path>();
        for(String path:options.get("--observations").split(",",-1)) {
            if(path.isBlank())throw new IllegalArgumentException("Blank observation path");
            observations.add(Path.of(path));
        }
        var service=new com.zoutrankil.data.factor.application.DailyMetricPipelineService(computation,publication);
        var result=options.containsKey("--date")?service.run(new com.zoutrankil.data.factor.application.DailyMetricPipelineService.Request(
                LocalDate.parse(options.get("--date")),LocalDate.parse(options.get("--as-of")),observations,
                Path.of(options.get("--pools")),options.get("--label"),Path.of(options.get("--root")))):
                service.runLatest(LocalDate.parse(options.get("--as-of")),observations,Path.of(options.get("--pools")),
                        options.get("--label"),Path.of(options.get("--root")),planner);
        if(freshnessPlan!=null)service.inspectPublished(result,freshnessPlan,freshness);
        CliOutput.printJson(JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(result.receipt(),
                1024*1024,()->new IllegalStateException("Pipeline receipt exceeds limit"))),CliOutput.Profile.JOB_DEFINITION,false);
        if(result.publication().state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED
                && result.publication().state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
            throw new IncompleteCommandException("Daily metric pipeline incomplete: "+result.publication().state()+"; receipt="+result.receipt());
    }
}
