package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.EtfMarketOverviewDailyCacheJobService;
import java.time.LocalDate;
import java.util.*;

/** Bounded D101 management commands for the unique delegated Python cache owner. */
final class EtfMarketOverviewDailyCacheCommands {
    private EtfMarketOverviewDailyCacheCommands() {}

    static void execute(String command, Map<String,String> options,
                        EtfMarketOverviewDailyCacheJobService owner) throws Exception {
        Objects.requireNonNull(owner, "Registered D101 owner required");
        switch (command) {
            case "etf-market-overview-cache-job-status", "cancel-etf-market-overview-cache-run",
                 "resume-etf-market-overview-cache-run" -> {
                requireKeys(options, Set.of("--run"));
                Object value = switch (command) {
                    case "etf-market-overview-cache-job-status" -> owner.status(options.get("--run"));
                    case "cancel-etf-market-overview-cache-run" -> owner.cancel(options.get("--run"));
                    default -> owner.resume(options.get("--run"));
                };
                CliOutput.printJson(value, CliOutput.Profile.JOB_DEFINITION, false);
                if (value instanceof EtfMarketOverviewDailyCacheJobService.MaterializationResult result)
                    requireComplete(result);
            }
            case "reconcile-etf-market-overview-cache-run" -> {
                requireKeys(options, Set.of("--run", "--writer-stopped"));
                if (!"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("writer-stopped must be true");
                CliOutput.printJson(owner.reconcile(options.get("--run"), true), CliOutput.Profile.JOB_DEFINITION, false);
            }
            case "plan-etf-market-overview-cache-job", "run-etf-market-overview-cache-job" -> {
                var required = Set.of("--from", "--to", "--logical-date");
                var allowed = new HashSet<>(required); allowed.add("--mode");
                if (!options.keySet().containsAll(required) || !allowed.containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit bootstrap from/to/logical-date and optional mode required");
                var plan = owner.plan(LocalDate.parse(options.get("--from")), LocalDate.parse(options.get("--to")),
                        LocalDate.parse(options.get("--logical-date")),
                        options.containsKey("--mode") ? Mode.valueOf(options.get("--mode")) : null);
                if (command.startsWith("plan-")) {
                    CliOutput.printJson(Map.of("status", "PLANNED", "executed", false,
                            "request", CliOutput.readTree(SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION),
                            "targetId", plan.targetId(), "source", plan.source()), CliOutput.Profile.JOB_DEFINITION, false);
                } else {
                    var result = owner.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, false);
                    requireComplete(result);
                }
            }
            default -> throw new IllegalArgumentException("Unknown D101 command");
        }
    }

    private static void requireKeys(Map<String,String> options, Set<String> expected) {
        if (!options.keySet().equals(expected))
            throw new IllegalArgumentException("Exact D101 options required: " + expected);
    }

    private static void requireComplete(EtfMarketOverviewDailyCacheJobService.MaterializationResult value) {
        if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(value.result().state()))
            throw new IncompleteCommandException("D101 incomplete: " + value.result().state());
    }
}
