package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.table.FactorDailyMetricRow;
import com.zoutrankil.data.factor.application.*;
import com.zoutrankil.data.factor.storage.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.service.*;
import io.questdb.client.QuestDB;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnNotWebApplication
public final class FactorDailyMetricPublicationCommands implements CliCommandFamily {
    private final JdbcTemplate jdbc;
    private final DailyMetricPublicationService publication;
    public FactorDailyMetricPublicationCommands(JdbcTemplate jdbc,@Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this.jdbc=jdbc;this.publication=new DailyMetricPublicationService(jdbc,questdb,Path.of(ledgerPath));
    }
    @Override public Set<String> commands(){return Set.of("prepare-factor-daily-metrics","run-factor-daily-metrics");}
    @Override public void execute(String command,Map<String,String> options)throws Exception {
        boolean publish=command.equals("run-factor-daily-metrics");
        var required=publish?Set.of("--computation","--evidence-root","--logical-date"):Set.of("--computation","--evidence-root");
        var allowed=new HashSet<>(required);
        if(publish)allowed.addAll(Set.of("--resume-from","--run-id"));
        if(!commands().contains(command) || !options.keySet().containsAll(required) || !allowed.containsAll(options.keySet()))
            throw new IllegalArgumentException("Expected --computation PATH --evidence-root DIRECTORY; run additionally requires --logical-date DATE [--resume-from RUN] [--run-id RUN]");
        String runId=options.getOrDefault("--run-id","factor-daily-metric-"+UUID.randomUUID());
        String prior=options.get("--resume-from");
        for(String id:List.of(runId,prior==null?runId:prior))
            if(!id.matches("factor-daily-metric-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
                throw new IllegalArgumentException("Canonical daily metric run identity required");
        LocalDate logical=publish?LocalDate.parse(options.get("--logical-date")):null;
        if(publish && logical.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))throw new IllegalArgumentException("Future logical date");
        if(!publish) {
            var prepared=DailyMetricPublicationPreparation.prepare(jdbc,Path.of(options.get("--computation")),Path.of(options.get("--evidence-root")));
            CliOutput.printJson(Map.of("evidence",prepared.evidence().toString(),"rows",prepared.rows(),
                    "inputFingerprint",prepared.guard().fingerprint(),"sourceComplete",false),CliOutput.Profile.PLAIN,false);
            return;
        }
        var result=publication.publishComputation(Path.of(options.get("--computation")),Path.of(options.get("--evidence-root")),logical,runId,prior);
        CliOutput.printJson(result,CliOutput.Profile.PLAIN,false);
        if(result.state()!=SyncRunState.VERIFIED && result.state()!=SyncRunState.VERIFIED_EMPTY)
            throw new IncompleteCommandException("Daily metric publication incomplete: "+result.state()+"; run="+runId);
    }

    /** Compatibility entry for callers; publication is owned by the application service. */
    public SyncJobRunner.Result publishComputation(Path computation,Path evidenceRoot,LocalDate logical,
            String runId,String prior)throws Exception {
        return publication.publishComputation(computation,evidenceRoot,logical,runId,prior);
    }
}
