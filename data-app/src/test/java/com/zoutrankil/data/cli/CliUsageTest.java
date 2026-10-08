package com.zoutrankil.data.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class CliUsageTest {
    @Test void retainsTheExactUsageTextCapturedBeforeCommandExtraction() throws Exception {
        String current = CliUsage.text();
        String usage = current.substring(0, current.indexOf(" OR sync-run-status"));
        assertEquals(8261, usage.length());
        assertEquals("c5385c0527d39ac32917bad609e613e9e2fb2071eb4cacaaf66783cb493beaf5",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(usage.getBytes(StandardCharsets.UTF_8))));
        assertTrue(usage.startsWith("Usage: run-exchange-calendar|plan-exchange-calendar "));
        assertTrue(usage.endsWith("cancel-sync-run --run ID [--ledger PATH]"));
        for (String command : java.util.List.of("sync-run-status", "plan-margin-detail-job",
                "finish-market-sentiment-publication", "finish-regime-monitor-publication"))
            assertTrue(current.contains(command));
    }
}
