package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.derived.application.MacroCoreMonthlyJobService;
import java.time.LocalDate;
import java.util.*;

/** Explicit first-of-month windows; setup and execution are separate management commands. */
final class MacroCoreMonthlyCommands {
    private MacroCoreMonthlyCommands() {}
    static void execute(String command, Map<String,String> options, MacroCoreMonthlyJobService owner) throws Exception {
        Objects.requireNonNull(owner,"Registered D104 owner required");
        switch (command) {
            case "install-macro-core-monthly-isolated" -> {
                requireKeys(options,Set.of()); owner.installIsolated();
                CliOutput.printJson(Map.of("status","ISOLATED_TARGET_READY","datasetId","macro_core_monthly"), CliOutput.Profile.JOB_DEFINITION, false);
            }
            case "macro-core-monthly-job-status", "cancel-macro-core-monthly-run",
                 "resume-macro-core-monthly-run", "reconcile-macro-core-monthly-run" -> {
                requireKeys(options,Set.of("--run"));
                Object value = switch (command) {
                    case "macro-core-monthly-job-status" -> owner.status(options.get("--run"));
                    case "cancel-macro-core-monthly-run" -> owner.cancel(options.get("--run"));
                    case "reconcile-macro-core-monthly-run" -> owner.reconcile(options.get("--run"));
                    default -> owner.resume(options.get("--run"));
                };
                CliOutput.printJson(value, CliOutput.Profile.JOB_DEFINITION, false);
                if(value instanceof MacroCoreMonthlyJobService.MaterializationResult result)requireComplete(result);
            }
            case "plan-macro-core-monthly-job", "run-macro-core-monthly-job" -> {
                var required=Set.of("--from","--to","--logical-date");var allowed=new HashSet<>(required);allowed.add("--mode");
                if(!options.keySet().containsAll(required) || !allowed.containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit first-month from/to/logical-date and optional mode required");
                var plan=owner.plan(LocalDate.parse(options.get("--from")),LocalDate.parse(options.get("--to")),
                        LocalDate.parse(options.get("--logical-date")),options.containsKey("--mode")?Mode.valueOf(options.get("--mode")):null);
                if(command.startsWith("plan-"))CliOutput.printJson(Map.of("status","PLANNED","executed",false,
                        "request",CliOutput.readTree(SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION),"targetId",plan.targetId(),"plan",plan), CliOutput.Profile.JOB_DEFINITION, false);
                else { var result=owner.run(plan);CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, false);requireComplete(result); }
            }
            default -> throw new IllegalArgumentException("Unknown D104 command");
        }
    }
    private static void requireKeys(Map<String,String> options, Set<String> expected) {
        if(!options.keySet().equals(expected))throw new IllegalArgumentException("Exact D104 command options required: "+expected);
    }
    private static void requireComplete(MacroCoreMonthlyJobService.MaterializationResult result) {
        if(result.targetSnapshotError()!=null)
            throw new IncompleteCommandException("D104 target observation failed: "+result.targetSnapshotError());
        if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(result.result().state()))
            throw new IncompleteCommandException("D104 incomplete: "+result.result().state());
    }
}
