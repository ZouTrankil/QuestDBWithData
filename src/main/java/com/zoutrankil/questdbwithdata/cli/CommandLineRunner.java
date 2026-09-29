package com.zoutrankil.questdbwithdata.cli;

import com.zoutrankil.questdbwithdata.domain.StockBasicSyncReport;
import com.zoutrankil.questdbwithdata.service.StockBasicSyncService;
import com.zoutrankil.questdbwithdata.service.DatasetRegistry;
import com.zoutrankil.questdbwithdata.service.SyncJobRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Component
public class CommandLineRunner implements ApplicationRunner {
    private final StockBasicSyncService syncService;
    private final DatasetRegistry datasetRegistry;
    private final SyncJobRegistry jobRegistry;

    public CommandLineRunner(StockBasicSyncService syncService, DatasetRegistry datasetRegistry,
                             SyncJobRegistry jobRegistry) {
        this.syncService = syncService;
        this.datasetRegistry = datasetRegistry;
        this.jobRegistry = jobRegistry;
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
                options.put(argument.substring(0, equals), argument.substring(equals + 1));
                continue;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + argument);
            }
            options.put(argument, args[++i]);
        }
        return options;
    }

    private static String usage() {
        return "Usage: sync-stock-basic [--output PATH] OR "
                + "sync-stock-basic-questdb OR migrate-questdb-schema OR "
                + "show-stock-basic-latest OR verify-questdb-jdbc OR show-dataset-definitions OR show-sync-job-definitions OR "
                + "show-sync-run --run ID [--ledger PATH] [--after ENTRY_ID] [--limit N]";
    }
}
