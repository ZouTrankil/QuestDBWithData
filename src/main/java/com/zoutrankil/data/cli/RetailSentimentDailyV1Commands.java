package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.*;
import java.time.LocalDate;
import java.util.*;

/** Canonical D098 lifecycle commands; every execution has an explicit finite date window. */
final class RetailSentimentDailyV1Commands {
    private RetailSentimentDailyV1Commands() {}
    static void execute(String command, Map<String,String> options, RetailSentimentDailyV1JobService owner) throws Exception {
        Objects.requireNonNull(owner, "Registered D098 owner required");
        var json = JobDefinitionJson.mapper();
        switch (command) {
            case "install-retail-sentiment-daily-isolated" -> {
                requireKeys(options, Set.of()); System.out.println(json.writeValueAsString(owner.installIsolated()));
            }
            case "repair-retail-sentiment-daily-isolated" -> {
                requireKeys(options, Set.of()); var result = owner.repairIsolated();
                System.out.println(json.writeValueAsString(result)); requireComplete(result);
            }
            case "retail-sentiment-daily-job-status", "cancel-retail-sentiment-daily-run", "resume-retail-sentiment-daily-run" -> {
                requireKeys(options, Set.of("--run"));
                var value = switch (command) {
                    case "retail-sentiment-daily-job-status" -> owner.status(options.get("--run"));
                    case "cancel-retail-sentiment-daily-run" -> owner.cancel(options.get("--run"));
                    default -> owner.resume(options.get("--run"));
                };
                System.out.println(json.writeValueAsString(value));
                if (value instanceof RetailSentimentDailyV1JobService.MaterializationResult result) requireComplete(result);
            }
            case "reconcile-retail-sentiment-daily-run" -> {
                requireKeys(options, Set.of("--run","--writer-stopped"));
                if (!"true".equals(options.get("--writer-stopped"))) throw new IllegalArgumentException("writer-stopped must be true");
                System.out.println(json.writeValueAsString(owner.reconcile(options.get("--run"), true)));
            }
            case "plan-retail-sentiment-daily-job", "run-retail-sentiment-daily-job" -> {
                var required = Set.of("--from","--to","--logical-date");
                var allowed = new HashSet<>(required); allowed.add("--mode");
                if (!options.keySet().containsAll(required) || !allowed.containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit from/to/logical-date and optional mode required");
                var plan = owner.plan(LocalDate.parse(options.get("--from")),LocalDate.parse(options.get("--to")),
                        LocalDate.parse(options.get("--logical-date")),options.containsKey("--mode") ? Mode.valueOf(options.get("--mode")) : null);
                if (command.startsWith("plan-")) {
                    System.out.println(json.writeValueAsString(Map.of("status","PLANNED","executed",false,
                            "request",json.readTree(SyncRequestIdentity.snapshotJson(plan.request())),
                            "targetId",plan.targetId(),"source",plan.source())));
                } else {
                    var result = owner.run(plan); System.out.println(json.writeValueAsString(result)); requireComplete(result);
                }
            }
            default -> throw new IllegalArgumentException("Unknown D098 command");
        }
    }
    private static void requireKeys(Map<String,String> options, Set<String> expected) {
        if (!options.keySet().equals(expected)) throw new IllegalArgumentException("Exact D098 options required: " + expected);
    }
    private static void requireComplete(RetailSentimentDailyV1JobService.MaterializationResult value) {
        if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(value.result().state()))
            throw new IncompleteCommandException("D098 incomplete: " + value.result().state());
    }
}
