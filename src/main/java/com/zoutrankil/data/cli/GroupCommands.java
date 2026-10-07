package com.zoutrankil.data.cli;

import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.ReadGroupReader;
import com.zoutrankil.data.domain.ReadGroupRequest;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.StockBasicDataset;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class GroupCommands implements CliCommandFamily {
    private static final java.util.Set<String> COMMANDS = java.util.Set.of(
            "run-sync-group", "plan-sync-group", "validate-sync-group",
            "write-dataset-group", "read-dataset-group", "read-stock-basic-group",
            "show-sync-group", "show-sync-group-definitions", "list-sync-groups",
            "run-stock-basic-group");
    private final SyncJobRegistry jobRegistry;
    private final com.zoutrankil.data.service.StockBasicGroupService groupService;
    private final ReadGroupReader readGroupReader;
    private final com.zoutrankil.data.service.StockBasicWriteGroupService writeGroupService;

    public GroupCommands(@org.springframework.context.annotation.Lazy SyncJobRegistry jobRegistry,
            com.zoutrankil.data.service.StockBasicGroupService groupService,
            ReadGroupReader readGroupReader,
            com.zoutrankil.data.service.StockBasicWriteGroupService writeGroupService) {
        this.jobRegistry = jobRegistry;
        this.groupService = groupService;
        this.readGroupReader = readGroupReader;
        this.writeGroupService = writeGroupService;
    }

    @Override public java.util.Set<String> commands() { return COMMANDS; }

    @Override public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "run-sync-group" -> {
                var planningOptions=new java.util.LinkedHashMap<>(options);
                String prior=planningOptions.remove("--resume-from");
                var groups=new com.zoutrankil.data.service.SyncGroupRegistry(groupService.definitions(),jobRegistry);
                var plan=com.zoutrankil.data.service.SyncGroupPlanning.prepare(groups,jobRegistry,planningOptions);
                var result=groupService.runPlan(plan,prior);
                CliOutput.printJson(result, CliOutput.Profile.PLAIN, false);
                if(result.state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED)
                    throw new IncompleteCommandException("Sync group incomplete: "+result.state());
            }
            case "plan-sync-group", "validate-sync-group" -> {
                var groups=new com.zoutrankil.data.service.SyncGroupRegistry(
                        groupService.definitions(),jobRegistry);
                var plan=com.zoutrankil.data.service.SyncGroupPlanning.prepare(groups,jobRegistry,options);
                var frozen=new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
                for (var request:plan.requests()) frozen.add(CliOutput.readTree(
                        com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(request), CliOutput.Profile.PLAIN));
                CliOutput.printJson(Map.of(
                        "status",command.equals("plan-sync-group")?"PLANNED":"VALIDATED",
                        "executed",false,"dataVerified",false,"group",plan.definition(),
                        "logicalDate",plan.logicalDate().toString(),"requests",frozen), CliOutput.Profile.PLAIN, true);
            }
            case "write-dataset-group" -> {
                if (!options.containsKey("--request")
                        || !java.util.Set.of("--request", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --request required; optional --resume-from");
                var result = writeGroupService.run(Path.of(options.get("--request")), options.get("--resume-from"));
                CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                if (result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                        && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                    throw new IncompleteCommandException("Write group incomplete: " + result.state() + "; run=" + result.runId());
            }
            case "read-dataset-group" -> {
                if (!options.keySet().equals(java.util.Set.of("--request")))
                    throw new IllegalArgumentException("Explicit --request JSON file required");
                var request = readGroupReader.readRequest(Path.of(options.get("--request")));
                var result = readGroupReader.read(request, () -> Thread.currentThread().isInterrupted());
                CliOutput.printJson(Map.of(
                                "complete", result.complete(), "atomicSnapshot", false, "result", result), CliOutput.Profile.MODULES_ISO, true);
                if (!result.complete()) throw new IncompleteCommandException("Read group contains unsuccessful members");
            }
            case "read-stock-basic-group" -> {
                if (!options.keySet().equals(java.util.Set.of("--codes", "--from", "--to", "--page-size")))
                    throw new IllegalArgumentException("Explicit --codes, --from, --to and --page-size are required");
                var codes = java.util.Arrays.asList(options.get("--codes").split(",", -1));
                var from = java.time.LocalDate.parse(options.get("--from")).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                var to = java.time.LocalDate.parse(options.get("--to")).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                int pageSize = Integer.parseInt(options.get("--page-size"));
                var projection = StockBasicDataset.DEFINITION.columns().stream()
                        .map(com.zoutrankil.data.domain.DatasetDefinition.Column::logicalName).toList();
                var members = new java.util.ArrayList<ReadGroupRequest.Member>();
                for (int i = 0; i < codes.size(); i++) {
                    var query = new DatasetReadQuery(projection, Map.of("ts_code", codes.get(i)),
                            "snapshot_ts", from, to, pageSize, null);
                    members.add(new ReadGroupRequest.Member("code" + i, StockBasicDataset.DEFINITION.datasetId(),
                            StockBasicDataset.DEFINITION.schemaVersion(), query));
                }
                var result = readGroupReader.read(new ReadGroupRequest(members, java.time.Duration.ofMinutes(1)), () -> false);
                CliOutput.printJson(result, CliOutput.Profile.MODULES_ISO, true);
                if (!result.complete()) throw new IncompleteCommandException("Read group contains failed members");
            }
            case "show-sync-group" -> {
                if (!options.keySet().equals(java.util.Set.of("--group", "--version")))
                    throw new IllegalArgumentException("Explicit group and version required");
                var registry = new com.zoutrankil.data.service.SyncGroupRegistry(groupService.definitions(), jobRegistry);
                CliOutput.printJson(
                        registry.require(options.get("--group"), Integer.parseInt(options.get("--version"))), CliOutput.Profile.PLAIN, true);
            }
            case "show-sync-group-definitions", "list-sync-groups" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("Group list accepts no options");
                CliOutput.printJson(groupService.definitions(), CliOutput.Profile.PLAIN, true);
            }
            case "run-stock-basic-group" -> {
                if (!options.keySet().containsAll(java.util.Set.of("--codes", "--logical-date"))
                        || !java.util.Set.of("--codes", "--logical-date", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --codes and --logical-date are required");
                var codes = java.util.Arrays.asList(options.get("--codes").split(",", -1));
                var day = java.time.LocalDate.parse(options.get("--logical-date"));
                var result = groupService.run(codes, day, options.get("--resume-from"));
                CliOutput.printJson(result, CliOutput.Profile.PLAIN, true);
                if (result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED)
                    throw new IncompleteCommandException("Group did not complete: " + result.state() + "; run=" + result.runId());
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
