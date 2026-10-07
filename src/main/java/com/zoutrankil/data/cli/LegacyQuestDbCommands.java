package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.StockBasicSyncReport;
import com.zoutrankil.data.stock.application.StockBasicSyncService;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class LegacyQuestDbCommands implements CliCommandFamily {
    private static final java.util.Set<String> COMMANDS = java.util.Set.of(
            "sync-stock-basic-questdb", "sync-stock-basic-qwp", "sync-stock-basic-jdbc",
            "migrate-questdb-schema", "create-questdb-schema", "show-stock-basic-latest",
            "verify-questdb-jdbc");
    private final StockBasicSyncService syncService;

    public LegacyQuestDbCommands(StockBasicSyncService syncService) {
        this.syncService = syncService;
    }

    @Override public java.util.Set<String> commands() { return COMMANDS; }

    @Override public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "sync-stock-basic-questdb", "sync-stock-basic-qwp", "sync-stock-basic-jdbc" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("Legacy QuestDB sync accepts no options");
                StockBasicSyncReport result = syncService.syncToQuestDb();
                System.out.printf(
                        "Tushare rows=%d; QuestDB visible rows=%d; snapshot=%s%n",
                        result.submittedRows(), result.visibleRows(),
                        result.snapshotTimestamp());
            }
            case "migrate-questdb-schema", "create-questdb-schema" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("Schema command accepts no options");
                syncService.initializeQuestDbSchema();
                System.out.println("QuestDB Flyway migrations completed");
            }
            case "show-stock-basic-latest" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("Stock-basic read accepts no options");
                var rows = syncService.loadLatestStocks();
                rows.forEach(System.out::println);
                System.out.printf("Read %d rows from latest-stock view%n", rows.size());
            }
            case "verify-questdb-jdbc" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("JDBC probe accepts no options");
                syncService.verifyQuestDbConnection();
                System.out.println("PostgreSQL JDBC connected; SELECT 1 passed");
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
