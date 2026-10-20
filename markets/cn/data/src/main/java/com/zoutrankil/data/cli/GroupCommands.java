package com.zoutrankil.data.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.ReadGroupRequest;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.stock.basic.StockBasicDataset;
import com.zoutrankil.data.service.ReadGroupReader;
import com.zoutrankil.data.service.StockBasicGroupService;
import com.zoutrankil.data.service.StockBasicWriteGroupService;
import com.zoutrankil.data.service.SyncGroupPlanning;
import com.zoutrankil.data.service.SyncGroupRegistry;
import com.zoutrankil.data.service.SyncJobRegistry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class GroupCommands implements CliCommandFamily {
    private static final Set<String> COMMANDS =
            Set.of(
                    "run-sync-group",
                    "plan-sync-group",
                    "validate-sync-group",
                    "write-dataset-group",
                    "read-dataset-group",
                    "read-stock-basic-group",
                    "show-sync-group",
                    "show-sync-group-definitions",
                    "list-sync-groups",
                    "run-stock-basic-group");
    private final SyncJobRegistry jobRegistry;
    private final StockBasicGroupService groupService;
    private final ReadGroupReader readGroupReader;
    private final StockBasicWriteGroupService writeGroupService;

    public GroupCommands(
            @Lazy SyncJobRegistry jobRegistry,
            StockBasicGroupService groupService,
            ReadGroupReader readGroupReader,
            StockBasicWriteGroupService writeGroupService) {
        this.jobRegistry = jobRegistry;
        this.groupService = groupService;
        this.readGroupReader = readGroupReader;
        this.writeGroupService = writeGroupService;
    }

    @Override
    public Set<String> commands() {
        return COMMANDS;
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "run-sync-group" -> {
                var planningOptions = new LinkedHashMap<>(options);
                String prior = planningOptions.remove("--resume-from");
                var groups = new SyncGroupRegistry(groupService.definitions(), jobRegistry);
                var plan = SyncGroupPlanning.prepare(groups, jobRegistry, planningOptions);
                var result = groupService.runPlan(plan, prior);
                CliOutput.printJson(result, CliOutput.Profile.PLAIN, false);
                if (result.state() != SyncRunState.VERIFIED)
                    throw new IncompleteCommandException(
                            "Sync group incomplete: " + result.state());
            }
            case "plan-sync-group", "validate-sync-group" -> {
                var groups = new SyncGroupRegistry(groupService.definitions(), jobRegistry);
                var plan = SyncGroupPlanning.prepare(groups, jobRegistry, options);
                var frozen = new ArrayList<JsonNode>();
                for (var request : plan.requests())
                    frozen.add(
                            CliOutput.readTree(
                                    SyncRequestIdentity.snapshotJson(request),
                                    CliOutput.Profile.PLAIN));
                CliOutput.printJson(
                        Map.of(
                                "status",
                                command.equals("plan-sync-group") ? "PLANNED" : "VALIDATED",
                                "executed",
                                false,
                                "dataVerified",
                                false,
                                "group",
                                plan.definition(),
                                "logicalDate",
                                plan.logicalDate().toString(),
                                "requests",
                                frozen),
                        CliOutput.Profile.PLAIN,
                        true);
            }
            case "write-dataset-group" -> {
                if (!options.containsKey("--request")
                        || !Set.of("--request", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException(
                            "Explicit --request required; optional --resume-from");
                var result =
                        writeGroupService.run(
                                Path.of(options.get("--request")), options.get("--resume-from"));
                CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                if (result.state() != SyncRunState.VERIFIED
                        && result.state() != SyncRunState.VERIFIED_EMPTY)
                    throw new IncompleteCommandException(
                            "Write group incomplete: "
                                    + result.state()
                                    + "; run="
                                    + result.runId());
            }
            case "read-dataset-group" -> {
                if (!options.keySet().equals(Set.of("--request")))
                    throw new IllegalArgumentException("Explicit --request JSON file required");
                var request = readGroupReader.readRequest(Path.of(options.get("--request")));
                var result =
                        readGroupReader.read(request, () -> Thread.currentThread().isInterrupted());
                CliOutput.printJson(
                        Map.of(
                                "complete",
                                result.complete(),
                                "atomicSnapshot",
                                false,
                                "result",
                                result),
                        CliOutput.Profile.MODULES_ISO,
                        true);
                if (!result.complete())
                    throw new IncompleteCommandException(
                            "Read group contains unsuccessful members");
            }
            case "read-stock-basic-group" -> {
                if (!options.keySet().equals(Set.of("--codes", "--from", "--to", "--page-size")))
                    throw new IllegalArgumentException(
                            "Explicit --codes, --from, --to and --page-size are required");
                var codes = Arrays.asList(options.get("--codes").split(",", -1));
                var from =
                        LocalDate.parse(options.get("--from"))
                                .atStartOfDay(ZoneOffset.UTC)
                                .toInstant();
                var to =
                        LocalDate.parse(options.get("--to"))
                                .atStartOfDay(ZoneOffset.UTC)
                                .toInstant();
                int pageSize = Integer.parseInt(options.get("--page-size"));
                var projection =
                        StockBasicDataset.DEFINITION.columns().stream()
                                .map(Column::logicalName)
                                .toList();
                var members = new ArrayList<ReadGroupRequest.Member>();
                for (int i = 0; i < codes.size(); i++) {
                    var query =
                            new DatasetReadQuery(
                                    projection,
                                    Map.of("ts_code", codes.get(i)),
                                    "snapshot_ts",
                                    from,
                                    to,
                                    pageSize,
                                    null);
                    members.add(
                            new ReadGroupRequest.Member(
                                    "code" + i,
                                    StockBasicDataset.DEFINITION.datasetId(),
                                    StockBasicDataset.DEFINITION.schemaVersion(),
                                    query));
                }
                var result =
                        readGroupReader.read(
                                new ReadGroupRequest(members, Duration.ofMinutes(1)), () -> false);
                CliOutput.printJson(result, CliOutput.Profile.MODULES_ISO, true);
                if (!result.complete())
                    throw new IncompleteCommandException("Read group contains failed members");
            }
            case "show-sync-group" -> {
                if (!options.keySet().equals(Set.of("--group", "--version")))
                    throw new IllegalArgumentException("Explicit group and version required");
                var registry = new SyncGroupRegistry(groupService.definitions(), jobRegistry);
                CliOutput.printJson(
                        registry.require(
                                options.get("--group"), Integer.parseInt(options.get("--version"))),
                        CliOutput.Profile.PLAIN,
                        true);
            }
            case "show-sync-group-definitions", "list-sync-groups" -> {
                if (!options.isEmpty())
                    throw new IllegalArgumentException("Group list accepts no options");
                CliOutput.printJson(groupService.definitions(), CliOutput.Profile.PLAIN, true);
            }
            case "run-stock-basic-group" -> {
                if (!options.keySet().containsAll(Set.of("--codes", "--logical-date"))
                        || !Set.of("--codes", "--logical-date", "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException(
                            "Explicit --codes and --logical-date are required");
                var codes = Arrays.asList(options.get("--codes").split(",", -1));
                var day = LocalDate.parse(options.get("--logical-date"));
                var result = groupService.run(codes, day, options.get("--resume-from"));
                CliOutput.printJson(result, CliOutput.Profile.PLAIN, true);
                if (result.state() != SyncRunState.VERIFIED)
                    throw new IncompleteCommandException(
                            "Group did not complete: "
                                    + result.state()
                                    + "; run="
                                    + result.runId());
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
