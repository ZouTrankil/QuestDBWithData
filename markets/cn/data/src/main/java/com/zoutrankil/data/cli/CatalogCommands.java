package com.zoutrankil.data.cli;

import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.SyncJobRegistry;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class CatalogCommands implements CliCommandFamily {
    private static final java.util.Set<String> COMMANDS = java.util.Set.of(
            "plan-sync-job", "validate-sync-job", "show-sync-job",
            "show-sync-job-definitions", "list-sync-jobs", "show-dataset-definitions");
    private final DatasetRegistry datasetRegistry;
    private final SyncJobRegistry jobRegistry;

    public CatalogCommands(DatasetRegistry datasetRegistry,
            @org.springframework.context.annotation.Lazy SyncJobRegistry jobRegistry) {
        this.datasetRegistry = datasetRegistry;
        this.jobRegistry = jobRegistry;
    }

    @Override public java.util.Set<String> commands() { return COMMANDS; }

    @Override public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "plan-sync-job", "validate-sync-job" -> {
                var request = com.zoutrankil.data.service.SyncJobPlanning.prepare(jobRegistry, options);
                String frozen = com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(request);
                CliOutput.printJson(Map.of(
                        "status", command.equals("plan-sync-job") ? "PLANNED" : "VALIDATED",
                        "executed", false, "dataVerified", false, "request", CliOutput.readTree(frozen, CliOutput.Profile.PLAIN)), CliOutput.Profile.PLAIN, true);
            }
            case "show-sync-job" -> {
                if (!options.keySet().equals(java.util.Set.of("--job", "--version")))
                    throw new IllegalArgumentException("Explicit job and version required");
                CliOutput.printJson(jobRegistry.require(
                                options.get("--job"), Integer.parseInt(options.get("--version"))), CliOutput.Profile.JOB_DEFINITION, true);
            }
            case "show-sync-job-definitions", "list-sync-jobs" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("Job list accepts no options");
                CliOutput.printJson(jobRegistry.definitions(), CliOutput.Profile.JOB_DEFINITION, true);
            }
            case "show-dataset-definitions" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("Dataset list accepts no options");
                CliOutput.printJson(datasetRegistry.definitions(), CliOutput.Profile.PLAIN, true);
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
