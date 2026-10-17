package com.zoutrankil.data.cli;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

class CliUsageTest {
    @Test void usageKeepsActiveCommandsAndDropsRetiredBacktestPublisher() throws Exception {
        String current = CliUsage.text();
        String usage = current.substring(0, current.indexOf(" OR sync-run-status"));
        assertFalse(current.contains("plan-backtest-daily-job"));
        assertFalse(current.contains("finish-backtest-daily-publication"));
        assertTrue(usage.startsWith("Usage: "));
        assertTrue(usage.contains("run-exchange-calendar|plan-exchange-calendar "));
        assertTrue(usage.endsWith("cancel-sync-run --run ID [--ledger PATH]"));
        for (String command : java.util.List.of("sync-run-status", "plan-margin-detail-job",
                "finish-market-sentiment-publication", "finish-regime-monitor-publication"))
            assertTrue(current.contains(command));
    }
}
