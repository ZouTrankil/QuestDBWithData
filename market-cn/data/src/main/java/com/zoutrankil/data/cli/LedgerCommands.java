package com.zoutrankil.data.cli;

import com.zoutrankil.data.service.LedgerManagementService;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class LedgerCommands implements CliCommandFamily {
    private static final java.util.Set<String> COMMANDS = java.util.Set.of(
            "sync-run-status", "cancel-sync-run", "show-sync-history", "show-sync-run");
    private final LedgerManagementService ledgerManagementService;

    public LedgerCommands(LedgerManagementService ledgerManagementService) {
        this.ledgerManagementService = ledgerManagementService;
    }

    @Override public java.util.Set<String> commands() { return COMMANDS; }

    @Override public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "sync-run-status" -> {
                if (!options.keySet().equals(java.util.Set.of("--run")))
                    throw new IllegalArgumentException("Exact --run required");
                var result = ledgerManagementService.status(options.get("--run"));
                CliOutput.printJson(Map.of("status", result.status(), "entries", result.entries()),
                        CliOutput.Profile.JOB_DEFINITION, true);
            }
            case "cancel-sync-run" -> {
                if (!java.util.Set.of("--ledger", "--run").containsAll(options.keySet()) || !options.containsKey("--run"))
                    throw new IllegalArgumentException("--run required; optional --ledger");
                var result = ledgerManagementService.cancel(options.get("--ledger"), options.get("--run"));
                CliOutput.printJson(Map.of("runId",result.runId(),
                        "cancellationRequested",result.cancellationRequested(),"state",result.state()), CliOutput.Profile.PLAIN, false);
            }
            case "show-sync-history" -> {
                if (!java.util.Set.of("--ledger", "--job", "--after", "--limit").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Unknown history option");
                var rows = ledgerManagementService.history(options.get("--ledger"), options.get("--job"), options.get("--after"),
                        Integer.parseInt(options.getOrDefault("--limit", "100")));
                CliOutput.printJson(Map.of(
                        "runs", rows, "order", "runIdAscending", "readOnly", true,
                        "nextAfter", rows.isEmpty() ? "" : rows.getLast().id()), CliOutput.Profile.PLAIN, true);
            }
            case "show-sync-run" -> {
                if (!java.util.Set.of("--ledger", "--run", "--after", "--limit").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Unknown status query option");
                String run = options.get("--run");
                if (run == null) throw new IllegalArgumentException("--run is required");
                int limit = Integer.parseInt(options.getOrDefault("--limit", "100"));
                var result = ledgerManagementService.run(options.get("--ledger"), run, options.get("--after"), limit);
                CliOutput.printJson(Map.of(
                        "run", result.run(), "entries", result.entries()), CliOutput.Profile.PLAIN, true);
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
