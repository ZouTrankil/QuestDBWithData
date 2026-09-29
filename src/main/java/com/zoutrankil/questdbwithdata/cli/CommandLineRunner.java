package com.zoutrankil.questdbwithdata.cli;

import com.zoutrankil.questdbwithdata.domain.StockBasicSyncReport;
import com.zoutrankil.questdbwithdata.service.StockBasicSyncService;
import com.zoutrankil.questdbwithdata.service.DatasetRegistry;
import com.zoutrankil.questdbwithdata.service.SyncJobRegistry;
import com.zoutrankil.questdbwithdata.service.ReadGroupReader;
import com.zoutrankil.questdbwithdata.domain.ReadGroupRequest;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.domain.StockBasicDataset;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Component
public class CommandLineRunner implements ApplicationRunner {
    private final StockBasicSyncService syncService;
    private final DatasetRegistry datasetRegistry;
    private final SyncJobRegistry jobRegistry;
    private final com.zoutrankil.questdbwithdata.service.StockBasicJobService jobService;
    private final com.zoutrankil.questdbwithdata.service.StockBasicGroupService groupService;
    private final ReadGroupReader readGroupReader;
    private final com.zoutrankil.questdbwithdata.service.StockBasicWriteGroupService writeGroupService;
    private final com.zoutrankil.questdbwithdata.service.StockBasicScheduleService scheduleService;

    @Autowired
    public CommandLineRunner(StockBasicSyncService syncService, DatasetRegistry datasetRegistry,
                             SyncJobRegistry jobRegistry, com.zoutrankil.questdbwithdata.service.StockBasicJobService jobService,
                             com.zoutrankil.questdbwithdata.service.StockBasicGroupService groupService,
                             ReadGroupReader readGroupReader,
                             com.zoutrankil.questdbwithdata.service.StockBasicWriteGroupService writeGroupService,
                             com.zoutrankil.questdbwithdata.service.StockBasicScheduleService scheduleService) {
        this.syncService = syncService;
        this.datasetRegistry = datasetRegistry;
        this.jobRegistry = jobRegistry;
        this.jobService = jobService;
        this.groupService = groupService;
        this.readGroupReader = readGroupReader;
        this.writeGroupService = writeGroupService;
        this.scheduleService = scheduleService;
    }

    public CommandLineRunner(StockBasicSyncService syncService, DatasetRegistry datasetRegistry,
                             SyncJobRegistry jobRegistry, com.zoutrankil.questdbwithdata.service.StockBasicJobService jobService,
                             com.zoutrankil.questdbwithdata.service.StockBasicGroupService groupService,
                             ReadGroupReader readGroupReader,
                             com.zoutrankil.questdbwithdata.service.StockBasicWriteGroupService writeGroupService) {
        this(syncService,datasetRegistry,jobRegistry,jobService,groupService,readGroupReader,writeGroupService,null);
    }

    @Override
    public void run(ApplicationArguments applicationArguments) throws Exception {
        String[] args = applicationArguments.getSourceArgs();
        if (args.length == 0) {
            throw new IllegalArgumentException(usage());
        }

        String command = args[0];
        Map<String, String> options = parseOptions(args);
        switch (command) {
            case "schedule-put" -> {
                if (!options.keySet().equals(java.util.Set.of("--request")))
                    throw new IllegalArgumentException("Explicit --request JSON required");
                scheduleService.put(Path.of(options.get("--request")));
                System.out.println("Schedule definition stored; no execution started");
            }
            case "schedule-status" -> {
                if (!options.keySet().equals(java.util.Set.of("--id")))
                    throw new IllegalArgumentException("Explicit --id required");
                System.out.println(new ObjectMapper().findAndRegisterModules()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(scheduleService.status(options.get("--id"))));
            }
            case "schedule-enable" -> {
                if (!options.keySet().equals(java.util.Set.of("--id","--enabled"))
                        || !java.util.Set.of("true","false").contains(options.get("--enabled")))
                    throw new IllegalArgumentException("Explicit --id and boolean --enabled required");
                scheduleService.setEnabled(options.get("--id"),Boolean.parseBoolean(options.get("--enabled")));
                System.out.println("Schedule enabled="+options.get("--enabled")+"; no execution started");
            }
            case "schedule-tick" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("schedule-tick has no options");
                var outcomes=scheduleService.tick();
                System.out.println(new ObjectMapper().findAndRegisterModules()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(outcomes));
                if (outcomes.stream().anyMatch(o -> o.state()!=com.zoutrankil.questdbwithdata.repository.SyncScheduleStore.State.VERIFIED
                        && o.state()!=com.zoutrankil.questdbwithdata.repository.SyncScheduleStore.State.VERIFIED_EMPTY))
                    throw new IllegalStateException("One or more schedule slots were not verified");
            }
            case "write-dataset-group" -> {
                if (!options.containsKey("--request")
                        || !java.util.Set.of("--request", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --request required; optional --resume-from");
                var result = writeGroupService.run(Path.of(options.get("--request")), options.get("--resume-from"));
                System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(result));
                if (result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                        && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                    throw new IllegalStateException("Write group incomplete: " + result.state() + "; run=" + result.runId());
            }
            case "read-dataset-group" -> {
                if (!options.keySet().equals(java.util.Set.of("--request")))
                    throw new IllegalArgumentException("Explicit --request JSON file required");
                var request = readGroupReader.readRequest(Path.of(options.get("--request")));
                var result = readGroupReader.read(request, () -> Thread.currentThread().isInterrupted());
                System.out.println(new ObjectMapper().findAndRegisterModules()
                        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                                "complete", result.complete(), "atomicSnapshot", false, "result", result)));
                if (!result.complete()) throw new IllegalStateException("Read group contains unsuccessful members");
            }
            case "read-stock-basic-group" -> {
                if (!options.keySet().equals(java.util.Set.of("--codes", "--from", "--to", "--page-size")))
                    throw new IllegalArgumentException("Explicit --codes, --from, --to and --page-size are required");
                var codes = java.util.Arrays.asList(options.get("--codes").split(",", -1));
                var from = java.time.LocalDate.parse(options.get("--from")).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                var to = java.time.LocalDate.parse(options.get("--to")).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                int pageSize = Integer.parseInt(options.get("--page-size"));
                var projection = StockBasicDataset.DEFINITION.columns().stream()
                        .map(com.zoutrankil.questdbwithdata.domain.DatasetDefinition.Column::logicalName).toList();
                var members = new java.util.ArrayList<ReadGroupRequest.Member>();
                for (int i = 0; i < codes.size(); i++) {
                    var query = new DatasetReadQuery(projection, Map.of("ts_code", codes.get(i)),
                            "snapshot_ts", from, to, pageSize, null);
                    members.add(new ReadGroupRequest.Member("code" + i, StockBasicDataset.DEFINITION.datasetId(),
                            StockBasicDataset.DEFINITION.schemaVersion(), query));
                }
                var result = readGroupReader.read(new ReadGroupRequest(members, java.time.Duration.ofMinutes(1)), () -> false);
                System.out.println(new ObjectMapper().findAndRegisterModules()
                        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result));
                if (!result.complete()) throw new IllegalStateException("Read group contains failed members");
            }
            case "show-sync-group-definitions" -> System.out.println(new ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(groupService.definitions()));
            case "run-stock-basic-group" -> {
                if (!options.keySet().containsAll(java.util.Set.of("--codes", "--logical-date"))
                        || !java.util.Set.of("--codes", "--logical-date", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --codes and --logical-date are required");
                var codes = java.util.Arrays.asList(options.get("--codes").split(",", -1));
                var day = java.time.LocalDate.parse(options.get("--logical-date"));
                var result = groupService.run(codes, day, options.get("--resume-from"));
                System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
                if (result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED)
                    throw new IllegalStateException("Group did not complete: " + result.state() + "; run=" + result.runId());
            }
            case "cancel-sync-run" -> {
                if (!java.util.Set.of("--ledger", "--run").containsAll(options.keySet()) || !options.containsKey("--run"))
                    throw new IllegalArgumentException("--run required; optional --ledger");
                Path path = Path.of(options.getOrDefault("--ledger", "var/sync-ledger.sqlite3"));
                if (!java.nio.file.Files.isRegularFile(path)) throw new IllegalArgumentException("Ledger does not exist");
                var ledger = new com.zoutrankil.questdbwithdata.repository.SyncRunLedger(path);
                boolean accepted = ledger.requestCancellation(options.get("--run"));
                System.out.println(new ObjectMapper().writeValueAsString(Map.of("runId",options.get("--run"),
                        "cancellationRequested",accepted,"state",ledger.get(options.get("--run")).state())));
            }
            case "run-stock-basic-job" -> {
                if (!options.keySet().containsAll(java.util.Set.of("--codes", "--logical-date"))
                        || !java.util.Set.of("--codes", "--logical-date", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --codes and --logical-date are required");
                var codes = java.util.Arrays.asList(options.get("--codes").split(",", -1));
                var day = java.time.LocalDate.parse(options.get("--logical-date"));
                var result = options.containsKey("--resume-from")
                        ? jobService.resume(codes,day,options.get("--resume-from")) : jobService.run(codes,day);
                System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
                if (result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                        && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                    throw new IllegalStateException("Sync did not complete: " + result.state() + "; run=" + result.runId());
            }
            case "show-sync-run" -> {
                if (!java.util.Set.of("--ledger", "--run", "--after", "--limit").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Unknown status query option");
                String run = options.get("--run");
                if (run == null) throw new IllegalArgumentException("--run is required");
                var ledger = com.zoutrankil.questdbwithdata.repository.SyncRunLedger.openReadOnly(
                        Path.of(options.getOrDefault("--ledger", "var/sync-ledger.sqlite3")));
                int limit = Integer.parseInt(options.getOrDefault("--limit", "100"));
                System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "run", ledger.getRun(run), "entries", ledger.entries(run, options.get("--after"), limit))));
            }
            case "show-sync-job-definitions" -> System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(jobRegistry.definitions()));
            case "show-dataset-definitions" -> System.out.println(new ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(datasetRegistry.definitions()));
            case "sync-stock-basic" -> {
                Path output = Path.of(options.getOrDefault("--output", "var/stock_basic.csv"));
                int rows = syncService.syncToCsv(output);
                System.out.printf("Fetched %d stocks; wrote %s%n", rows, output.toAbsolutePath());
            }
            case "sync-stock-basic-questdb", "sync-stock-basic-qwp", "sync-stock-basic-jdbc" -> {
                StockBasicSyncReport result = syncService.syncToQuestDb();
                System.out.printf(
                        "Tushare rows=%d; QuestDB visible rows=%d; snapshot=%s%n",
                        result.submittedRows(), result.visibleRows(),
                        result.snapshotTimestamp());
            }
            case "migrate-questdb-schema", "create-questdb-schema" -> {
                syncService.initializeQuestDbSchema();
                System.out.println("QuestDB Flyway migrations completed");
            }
            case "show-stock-basic-latest" -> {
                var rows = syncService.loadLatestStocks();
                rows.forEach(System.out::println);
                System.out.printf("Read %d rows from latest-stock view%n", rows.size());
            }
            case "verify-questdb-jdbc" -> {
                syncService.verifyQuestDbConnection();
                System.out.println("PostgreSQL JDBC connected; SELECT 1 passed");
            }
            default -> throw new IllegalArgumentException(usage());
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + argument);
            }
            int equals = argument.indexOf('=');
            if (equals > 2) {
                putOption(options, argument.substring(0, equals), argument.substring(equals + 1));
                continue;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + argument);
            }
            putOption(options, argument, args[++i]);
        }
        return options;
    }

    private static void putOption(Map<String,String> options, String key, String value) {
        if (!key.matches("--[a-z][a-z0-9-]*") || value.isBlank())
            throw new IllegalArgumentException("Invalid or empty option: " + key);
        if (options.putIfAbsent(key, value) != null)
            throw new IllegalArgumentException("Duplicate option: " + key);
    }

    private static String usage() {
        return "Usage: sync-stock-basic [--output PATH] OR "
                + "sync-stock-basic-questdb OR migrate-questdb-schema OR "
                + "show-stock-basic-latest OR verify-questdb-jdbc OR show-dataset-definitions OR show-sync-job-definitions OR "
                + "show-sync-group-definitions OR run-stock-basic-group --codes CODE,CODE --logical-date YYYY-MM-DD "
                + "[--resume-from GROUP_RUN_ID] OR "
                + "read-dataset-group --request PATH OR "
                + "write-dataset-group --request PATH [--resume-from RUN_ID] OR "
                + "schedule-put --request PATH OR schedule-status --id ID OR "
                + "schedule-enable --id ID --enabled true|false OR schedule-tick OR "
                + "read-stock-basic-group --codes CODE,CODE --from YYYY-MM-DD --to YYYY-MM-DD --page-size N OR "
                + "show-sync-run --run ID [--ledger PATH] [--after ENTRY_ID] [--limit N] OR "
                + "run-stock-basic-job --codes CODE,CODE --logical-date YYYY-MM-DD [--resume-from RUN_ID] OR cancel-sync-run --run ID [--ledger PATH]";
    }
}
