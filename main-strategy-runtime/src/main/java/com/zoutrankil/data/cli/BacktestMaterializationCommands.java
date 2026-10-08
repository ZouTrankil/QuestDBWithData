package com.zoutrankil.data.cli;

import com.zoutrankil.data.service.BacktestDailyMaterializationJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/** Commands for the canonical base and public native backtest materialization. */
@Component
@ConditionalOnNotWebApplication
public final class BacktestMaterializationCommands implements CliCommandFamily {
    private static final Set<String> COMMANDS = Set.of(
            "plan-backtest-daily-job", "run-backtest-daily-job", "finish-backtest-daily-publication");

    private final BacktestDailyMaterializationJobService service;

    @Autowired
    public BacktestMaterializationCommands(@Nullable BacktestDailyMaterializationJobService service) {
        this.service = service;
    }

    @Override public Set<String> commands() { return COMMANDS; }

    @Override public void execute(String command, Map<String, String> options) throws Exception {
        if (command.equals("finish-backtest-daily-publication")) {
            if (service == null || !options.keySet().equals(Set.of("--run", "--writer-stopped"))
                    || !"true".equals(options.get("--writer-stopped")))
                throw new IllegalArgumentException("Exact --run and explicit --writer-stopped true required");
            CliOutput.printJson(service.finishInterrupted(options.get("--run"), true), CliOutput.Profile.JOB_DEFINITION, true);
            return;
        }

        if (service == null) throw new IllegalStateException("Backtest materialization owner unavailable");
        boolean bootstrap = "true".equals(options.get("--bootstrap"));
        if (bootstrap ? !options.keySet().equals(Set.of("--bootstrap", "--logical-date"))
                : !options.keySet().equals(Set.of("--from", "--to", "--logical-date")))
            throw new IllegalArgumentException("Exact --from/--to/--logical-date, or --bootstrap true/--logical-date required");
        LocalDate logicalDate = LocalDate.parse(options.get("--logical-date"));
        var plan = bootstrap ? service.bootstrapPlan(logicalDate)
                : service.plan(LocalDate.parse(options.get("--from")), LocalDate.parse(options.get("--to")), logicalDate);
        if (command.equals("plan-backtest-daily-job")) {
            CliOutput.printJson(Map.of("status", "PLANNED", "executed", false, "dataVerified", false, "plan", plan),
                    CliOutput.Profile.JOB_DEFINITION, true);
            return;
        }

        var materialization = service.run(plan);
        CliOutput.printJson(materialization, CliOutput.Profile.JOB_DEFINITION, true);
        var result = materialization.result();
        if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED)
            throw new IncompleteCommandException("Backtest materialization incomplete: " + result.state() + "; run=" + result.runId());
    }
}
