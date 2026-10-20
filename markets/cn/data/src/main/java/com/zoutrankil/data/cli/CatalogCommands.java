package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.SyncJobPlanning;
import com.zoutrankil.data.service.SyncJobRegistry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class CatalogCommands implements CliCommandFamily {
    private static final Set<String> COMMANDS =
            Set.of(
                    "plan-sync-job",
                    "validate-sync-job",
                    "show-sync-job",
                    "show-sync-job-definitions",
                    "list-sync-jobs",
                    "show-dataset-definitions");
    private final DatasetRegistry datasetRegistry;
    private final SyncJobRegistry jobRegistry;

    public CatalogCommands(DatasetRegistry datasetRegistry, @Lazy SyncJobRegistry jobRegistry) {
        this.datasetRegistry = datasetRegistry;
        this.jobRegistry = jobRegistry;
    }

    @Override
    public Set<String> commands() {
        return COMMANDS;
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "plan-sync-job", "validate-sync-job" -> {
                var request = SyncJobPlanning.prepare(jobRegistry, options);
                String frozen = SyncRequestIdentity.snapshotJson(request);
                CliOutput.printJson(
                        Map.of(
                                "status",
                                command.equals("plan-sync-job") ? "PLANNED" : "VALIDATED",
                                "executed",
                                false,
                                "dataVerified",
                                false,
                                "request",
                                CliOutput.readTree(frozen, CliOutput.Profile.PLAIN)),
                        CliOutput.Profile.PLAIN,
                        true);
            }
            case "show-sync-job" -> {
                if (!options.keySet().equals(Set.of("--job", "--version")))
                    throw new IllegalArgumentException("Explicit job and version required");
                CliOutput.printJson(
                        jobRegistry.require(
                                options.get("--job"), Integer.parseInt(options.get("--version"))),
                        CliOutput.Profile.JOB_DEFINITION,
                        true);
            }
            case "show-sync-job-definitions", "list-sync-jobs" -> {
                if (!options.isEmpty())
                    throw new IllegalArgumentException("Job list accepts no options");
                CliOutput.printJson(
                        jobRegistry.definitions(), CliOutput.Profile.JOB_DEFINITION, true);
            }
            case "show-dataset-definitions" -> {
                if (!options.isEmpty())
                    throw new IllegalArgumentException("Dataset list accepts no options");
                CliOutput.printJson(datasetRegistry.definitions(), CliOutput.Profile.PLAIN, true);
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
