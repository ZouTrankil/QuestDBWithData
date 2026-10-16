package com.zoutrankil.data.cli;

import com.zoutrankil.data.l2.application.FormalL2DailyFeaturesIngestion;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

/** Explicit single-date entrypoints for the native formal L2 owner. */
@Component
@ConditionalOnNotWebApplication
public final class FormalL2DailyCommands implements CliCommandFamily {
    private final FormalL2DailyFeaturesIngestion ingestion;
    public FormalL2DailyCommands(FormalL2DailyFeaturesIngestion ingestion) { this.ingestion=ingestion; }
    @Override public Set<String> commands() {
        return Set.of("validate-formal-l2-day","ingest-formal-l2-day","ingest-formal-l2-jsonl-day",
                "reconcile-formal-l2-jsonl-day");
    }
    @Override public void execute(String command,Map<String,String> options)throws Exception {
        if (command.equals("reconcile-formal-l2-jsonl-day")) {
            if(!options.keySet().equals(Set.of("--date","--source-root","--run-id")))
                throw new IllegalArgumentException("Expected --date YYYY-MM-DD --source-root PATH --run-id PRIOR_RUN_ID");
            LocalDate day=LocalDate.parse(options.get("--date"));
            var reconciliation=ingestion.reconcileNativeJsonlInDoubt(day,Path.of(options.get("--source-root")),
                    options.get("--run-id"),()->Thread.currentThread().isInterrupted());
            CliOutput.printJson(Map.of("operation",command,"readOnly",true,"reconciliation",reconciliation),
                    CliOutput.Profile.JOB_DEFINITION,true);
            return;
        }
        if (command.equals("ingest-formal-l2-jsonl-day")) {
            if(!options.keySet().equals(Set.of("--date","--source-root")))
                throw new IllegalArgumentException("Expected --date YYYY-MM-DD --source-root PATH");
            LocalDate day=LocalDate.parse(options.get("--date"));
            if(day.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
                throw new IllegalArgumentException("Current or historical L2 date required");
            var evidence=ingestion.ingestNativeJsonlAndValidate(day,Path.of(options.get("--source-root")),
                    ()->Thread.currentThread().isInterrupted());
            CliOutput.printJson(Map.of("operation",command,"readOnly",false,"evidence",evidence,
                    "coverageScope","COMPLETE native parser manifest universe","factorReady",false),
                    CliOutput.Profile.JOB_DEFINITION,true);
            return;
        }
        if(!commands().contains(command) || !options.keySet().equals(Set.of("--date")))
            throw new IllegalArgumentException("Expected --date YYYY-MM-DD");
        LocalDate day=LocalDate.parse(options.get("--date"));
        if(day.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            throw new IllegalArgumentException("Current or historical L2 date required");
        boolean write=command.equals("ingest-formal-l2-day");
        var evidence=write?ingestion.ingestAndValidate(day,()->Thread.currentThread().isInterrupted())
                :ingestion.validate(day,()->Thread.currentThread().isInterrupted());
        CliOutput.printJson(Map.of("operation",command,"readOnly",!write,"evidence",evidence,
                "coverageScope","selected certified manifest universe","factorReady",false),CliOutput.Profile.JOB_DEFINITION,true);
    }
}
