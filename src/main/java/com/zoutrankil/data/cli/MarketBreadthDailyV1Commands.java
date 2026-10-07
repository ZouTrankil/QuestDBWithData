package com.zoutrankil.data.cli;

import com.zoutrankil.data.derived.application.MarketBreadthDailyV1JobService;

import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.*;
import java.time.LocalDate;
import java.util.*;

/** Canonical D095 lifecycle commands; every execution has an explicit finite date window. */
final class MarketBreadthDailyV1Commands {
    private MarketBreadthDailyV1Commands() {}
    static void execute(String command, Map<String,String> options, MarketBreadthDailyV1JobService owner) throws Exception {
        Objects.requireNonNull(owner, "Registered D095 owner required");
        switch (command) {
            case "install-market-breadth-daily-isolated" -> {
                requireKeys(options, Set.of()); CliOutput.printJson(owner.installIsolated(), CliOutput.Profile.JOB_DEFINITION, false);
            }
            case "repair-market-breadth-daily-isolated" -> {
                requireKeys(options, Set.of()); var result = owner.repairIsolated();
                CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, false); requireComplete(result);
            }
            case "market-breadth-daily-job-status", "cancel-market-breadth-daily-run", "resume-market-breadth-daily-run" -> {
                requireKeys(options, Set.of("--run"));
                var value = switch (command) {
                    case "market-breadth-daily-job-status" -> owner.status(options.get("--run"));
                    case "cancel-market-breadth-daily-run" -> owner.cancel(options.get("--run"));
                    default -> owner.resume(options.get("--run"));
                };
                CliOutput.printJson(value, CliOutput.Profile.JOB_DEFINITION, false);
                if (value instanceof MarketBreadthDailyV1JobService.MaterializationResult result) requireComplete(result);
            }
            case "reconcile-market-breadth-daily-run" -> {
                requireKeys(options, Set.of("--run","--writer-stopped"));
                if (!"true".equals(options.get("--writer-stopped"))) throw new IllegalArgumentException("writer-stopped must be true");
                CliOutput.printJson(owner.reconcile(options.get("--run"), true), CliOutput.Profile.JOB_DEFINITION, false);
            }
            case "plan-market-breadth-daily-job", "run-market-breadth-daily-job" -> {
                var required = Set.of("--from","--to","--logical-date");
                var allowed = new HashSet<>(required); allowed.add("--mode");
                if (!options.keySet().containsAll(required) || !allowed.containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit from/to/logical-date and optional mode required");
                var plan = owner.plan(LocalDate.parse(options.get("--from")),LocalDate.parse(options.get("--to")),
                        LocalDate.parse(options.get("--logical-date")),options.containsKey("--mode") ? Mode.valueOf(options.get("--mode")) : null);
                if (command.startsWith("plan-")) {
                    CliOutput.printJson(Map.of("status","PLANNED","executed",false,
                            "request",CliOutput.readTree(SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION),
                            "targetId",plan.targetId(),"source",plan.source()), CliOutput.Profile.JOB_DEFINITION, false);
                } else {
                    var result = owner.run(plan); CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, false); requireComplete(result);
                }
            }
            default -> throw new IllegalArgumentException("Unknown D095 command");
        }
    }
    private static void requireKeys(Map<String,String> options, Set<String> expected) {
        if (!options.keySet().equals(expected)) throw new IllegalArgumentException("Exact D095 options required: " + expected);
    }
    private static void requireComplete(MarketBreadthDailyV1JobService.MaterializationResult value) {
        if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(value.result().state()))
            throw new IncompleteCommandException("D095 incomplete: " + value.result().state());
    }
}
