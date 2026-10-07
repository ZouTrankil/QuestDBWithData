package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.EquityStyleMonthlyJobService;
import java.time.LocalDate;
import java.util.*;

/** Explicit first-of-month windows; setup and execution are separate management commands. */
final class EquityStyleMonthlyCommands {
    private EquityStyleMonthlyCommands() {}
    static void execute(String command, Map<String,String> options, EquityStyleMonthlyJobService owner) throws Exception {
        Objects.requireNonNull(owner,"Registered D103 owner required");
        switch (command) {
            case "install-equity-style-monthly-isolated" -> {
                requireKeys(options,Set.of()); owner.installIsolated();
                CliOutput.printJson(Map.of("status","ISOLATED_TARGET_READY","datasetId","equity_style_monthly"), CliOutput.Profile.JOB_DEFINITION, false);
            }
            case "equity-style-monthly-job-status", "cancel-equity-style-monthly-run",
                 "resume-equity-style-monthly-run", "reconcile-equity-style-monthly-run" -> {
                requireKeys(options,Set.of("--run"));
                Object value = switch (command) {
                    case "equity-style-monthly-job-status" -> owner.status(options.get("--run"));
                    case "cancel-equity-style-monthly-run" -> owner.cancel(options.get("--run"));
                    case "reconcile-equity-style-monthly-run" -> owner.reconcile(options.get("--run"));
                    default -> owner.resume(options.get("--run"));
                };
                CliOutput.printJson(value, CliOutput.Profile.JOB_DEFINITION, false);
                if(value instanceof EquityStyleMonthlyJobService.MaterializationResult result)requireComplete(result);
            }
            case "plan-equity-style-monthly-job", "run-equity-style-monthly-job" -> {
                var required=Set.of("--from","--to","--logical-date");var allowed=new HashSet<>(required);allowed.add("--mode");
                if(!options.keySet().containsAll(required) || !allowed.containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit first-month from/to/logical-date and optional mode required");
                var plan=owner.plan(LocalDate.parse(options.get("--from")),LocalDate.parse(options.get("--to")),
                        LocalDate.parse(options.get("--logical-date")),options.containsKey("--mode")?Mode.valueOf(options.get("--mode")):null);
                if(command.startsWith("plan-"))CliOutput.printJson(Map.of("status","PLANNED","executed",false,
                        "request",CliOutput.readTree(SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION),"targetId",plan.targetId(),"plan",plan), CliOutput.Profile.JOB_DEFINITION, false);
                else { var result=owner.run(plan);CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, false);requireComplete(result); }
            }
            default -> throw new IllegalArgumentException("Unknown D103 command");
        }
    }
    private static void requireKeys(Map<String,String> options, Set<String> expected) {
        if(!options.keySet().equals(expected))throw new IllegalArgumentException("Exact D103 command options required: "+expected);
    }
    private static void requireComplete(EquityStyleMonthlyJobService.MaterializationResult result) {
        if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(result.result().state()))
            throw new IncompleteCommandException("D103 incomplete: "+result.result().state());
    }
}
