package com.zoutrankil.data.cli;
import com.zoutrankil.data.index.application.IndexWeightCapturedJob;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.domain.SyncRunState;
import io.questdb.client.QuestDB;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnNotWebApplication
public final class CapturedIndexWeightCommands implements CliCommandFamily {
    private final JdbcTemplate jdbc;private final QuestDB questdb;private final Path ledger;
    public CapturedIndexWeightCommands(JdbcTemplate jdbc,@Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger){
        this.jdbc=jdbc;this.questdb=questdb;this.ledger=Path.of(ledger);
    }
    public Set<String> commands(){return Set.of("plan-captured-index-weight","run-captured-index-weight");}
    public void execute(String command,Map<String,String> options)throws Exception {
        if(!commands().contains(command) || !options.keySet().equals(Set.of("--source","--logical-date","--evidence-root")))
            throw new IllegalArgumentException("Expected --source CAPTURED_JSON --logical-date DATE --evidence-root NEW_DIRECTORY");
        var result=IndexWeightCapturedJob.execute(jdbc,questdb,ledger,Path.of(options.get("--source")),
                Path.of(options.get("--evidence-root")),LocalDate.parse(options.get("--logical-date")),command.startsWith("run-"));
        CliOutput.printJson(result,CliOutput.Profile.JOB_DEFINITION,true);
        if(result.get("result") instanceof SyncJobRunner.Result publication && publication.state()!=SyncRunState.VERIFIED)
            throw new IncompleteCommandException("Captured index weights: "+publication.state()+"; run="+publication.runId());
    }
}
