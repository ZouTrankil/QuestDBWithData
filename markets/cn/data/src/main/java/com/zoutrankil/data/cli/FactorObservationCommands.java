package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.factor.application.FactorObservationEvidence;
import com.zoutrankil.data.factor.application.FactorObservationJobService;
import com.zoutrankil.data.factor.storage.GlobalObservationWriteSession;
import com.zoutrankil.data.service.SyncJobRunner;
import io.questdb.client.QuestDB;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Explicit frozen-result publication; does not start an automatic schedule. */
@Component
@ConditionalOnNotWebApplication
public final class FactorObservationCommands implements CliCommandFamily {
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final Path ledgerPath;
    private final com.zoutrankil.data.factor.application.FactorObservationPublicationService publication;
    public FactorObservationCommands(JdbcTemplate jdbc,@Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this.jdbc=jdbc;this.questdb=questdb;this.ledgerPath=Path.of(ledgerPath);
        this.publication=new com.zoutrankil.data.factor.application.FactorObservationPublicationService(jdbc,questdb,this.ledgerPath);
    }
    @Override public Set<String> commands() { return Set.of("plan-factor-observations","run-factor-observations","reconcile-factor-observations","inspect-factor-observation-page"); }
    @Override public void execute(String command,Map<String,String> options) throws Exception {
        if(command.equals("inspect-factor-observation-page")) {
            if(!options.keySet().equals(Set.of("--evidence","--page"))
                    && !options.keySet().equals(Set.of("--evidence","--page","--job-version")))
                throw new IllegalArgumentException("Expected --evidence PATH --page INDEX [--job-version 2|3]");
            var snapshot=FactorObservationEvidence.read(Path.of(options.get("--evidence")));
            int page=Integer.parseInt(options.get("--page"));
            int version=Integer.parseInt(options.getOrDefault("--job-version","2"));
            int pageRows=FactorObservationJobService.pageRows(version);
            if(page<0 || page>=400 || page*pageRows>=snapshot.rows().size())throw new IllegalArgumentException("Page outside evidence");
            var expected=snapshot.rows().subList(page*pageRows,Math.min((page+1)*pageRows,snapshot.rows().size()));
            var session=new GlobalObservationWriteSession(jdbc,questdb);
            var physical=session.readback(expected.stream().map(com.zoutrankil.data.factor.application.GlobalObservationCoverage::key).toList());
            var delta=com.zoutrankil.data.factor.application.GlobalObservationCoverage.compare(expected,physical);
            CliOutput.printJson(Map.of("readOnly",true,"page",page,"jobVersion",version,"expectedRows",expected.size(),"physicalRows",physical.size(),
                    "matchingRows",delta.matching(),"pendingRows",delta.writes().size(),"unexpectedRows",delta.unexpected()),CliOutput.Profile.PLAIN,false);
            return;
        }
        if(command.equals("reconcile-factor-observations")) {
            if(!options.keySet().equals(Set.of("--run","--evidence","--writer-log","--proof")))
                throw new IllegalArgumentException("Expected --run RUN --evidence PATH --writer-log PATH --proof NEW_PATH");
            CliOutput.printJson(com.zoutrankil.data.factor.application.FactorObservationRecovery.reconcile(
                    ledgerPath,options.get("--run"),Path.of(options.get("--evidence")),Path.of(options.get("--writer-log")),
                    Path.of(options.get("--proof")),new GlobalObservationWriteSession(jdbc,questdb),
                    new com.zoutrankil.data.factor.storage.QuestDbGlobalObservations(jdbc)),CliOutput.Profile.PLAIN,false);
            return;
        }
        if(!commands().contains(command) || !options.keySet().containsAll(Set.of("--evidence","--logical-date"))
                || !Set.of("--evidence","--logical-date","--mode","--resume-from").containsAll(options.keySet()))
            throw new IllegalArgumentException("Expected --evidence PATH --logical-date DATE [--mode MODE] [--resume-from RUN]");
        boolean plan=command.equals("plan-factor-observations");
        if(plan && options.containsKey("--resume-from")) throw new IllegalArgumentException("Resume belongs to run command");
        Path evidence=Path.of(options.get("--evidence")).toAbsolutePath().normalize();
        var snapshot=FactorObservationEvidence.read(evidence);
        LocalDate from=snapshot.rows().stream().map(row->row.signalDate()).min(LocalDate::compareTo).orElseThrow();
        LocalDate to=snapshot.rows().stream().map(row->row.signalDate()).max(LocalDate::compareTo).orElseThrow();
        LocalDate logicalDate=LocalDate.parse(options.get("--logical-date"));
        if(to.isAfter(logicalDate)) throw new IllegalArgumentException("Future observation window");
        var mode=SyncJobDefinition.Mode.valueOf(options.getOrDefault("--mode","INCREMENTAL"));
        if(!plan) {
            var result=publishEvidence(evidence,logicalDate,mode,options.get("--resume-from"),"factor-observation-"+UUID.randomUUID());
            CliOutput.printJson(result,CliOutput.Profile.PLAIN,false);
            if(result.state()!=SyncRunState.VERIFIED && result.state()!=SyncRunState.VERIFIED_EMPTY)
                throw new IncompleteCommandException("Factor observation publication incomplete: "+result.state());
            return;
        }
        var session=new GlobalObservationWriteSession(jdbc,questdb);
        String target=session.targetIdentity();
        var request=FactorObservationJobService.DEFINITION.freeze(mode,
                Map.of("targetId",target,"factorEvidenceSha256",snapshot.fileSha256()),from,to,logicalDate);
        if(plan) {
            CliOutput.printJson(Map.of("executed",false,"rows",snapshot.rows().size(),"sourceComplete",snapshot.sourceComplete(),
                    "request",CliOutput.readTree(SyncRequestIdentity.snapshotJson(request),CliOutput.Profile.PLAIN)),
                    CliOutput.Profile.JOB_DEFINITION,true);
            return;
        }
    }

    /** Compatibility entry for finite pipelines; publication lives in the application service. */
    public void requireResumableEvidence(Path evidence,LocalDate logicalDate,String priorRunId) throws Exception {
        publication.requireResumableEvidence(evidence,logicalDate,priorRunId);
    }
    public SyncJobRunner.Result publishEvidence(Path evidence,LocalDate logicalDate,SyncJobDefinition.Mode mode,
            String priorRunId,String runId) throws Exception {
        return publication.publishEvidence(evidence,logicalDate,mode,priorRunId,runId);
    }
}
