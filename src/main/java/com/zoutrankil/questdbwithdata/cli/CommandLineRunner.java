package com.zoutrankil.questdbwithdata.cli;

import com.zoutrankil.questdbwithdata.domain.StockBasicSyncReport;
import com.zoutrankil.questdbwithdata.service.StockBasicSyncService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Component
public class CommandLineRunner implements ApplicationRunner {
    private final StockBasicSyncService syncService;

    public CommandLineRunner(StockBasicSyncService syncService) {
        this.syncService = syncService;
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
            case "create-questdb-schema" -> {
                syncService.initializeQuestDbSchema();
                System.out.println("QuestDB table and views are ready");
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
                + "sync-stock-basic-questdb OR create-questdb-schema OR "
                + "show-stock-basic-latest OR verify-questdb-jdbc";
    }
}
