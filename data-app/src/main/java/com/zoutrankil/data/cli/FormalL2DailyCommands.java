package com.zoutrankil.data.cli;

import com.zoutrankil.data.l2.application.FormalL2DailyFeaturesIngestion;
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
        return Set.of("validate-formal-l2-day","ingest-formal-l2-day");
    }
    @Override public void execute(String command,Map<String,String> options)throws Exception {
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
