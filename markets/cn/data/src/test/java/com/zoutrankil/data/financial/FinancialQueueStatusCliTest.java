package com.zoutrankil.data.financial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.cli.FinancialSyncCommands;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.financial.application.FinancialDailyQueue;
import com.zoutrankil.data.financial.application.FinancialSyncOwner;
import com.zoutrankil.data.financial.config.FinancialQueueFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@ResourceLock("java.lang.System.out")
class FinancialQueueStatusCliTest {
    @TempDir Path root;

    @Test
    void activeQueuePrintsItsRealDatesAndAllowanceAsJson() throws Exception {
        Path ledger = root.resolve("ledger.sqlite3");
        var date = LocalDate.parse("2026-10-20");
        var period = LocalDate.parse("2026-06-30");
        FinancialQueueFactory.forLedger(ledger)
                .run(
                        "a".repeat(64),
                        date,
                        List.of(
                                new FinancialDailyQueue.Slice("income", "600000.SH", period),
                                new FinancialDailyQueue.Slice("cashflow", "600000.SH", period)),
                        3,
                        3,
                        Duration.ofMinutes(1),
                        new FinancialDailyQueue.Executor() {
                            public String targetFingerprint() {
                                return "fixture-table";
                            }

                            public void run(
                                    FinancialDailyQueue.Slice slice,
                                    LocalDate day,
                                    String id,
                                    Duration remaining) {}

                            public boolean verified(
                                    FinancialDailyQueue.Slice slice, LocalDate day, String id) {
                                return true;
                            }
                        });
        var owner = mock(FinancialSyncOwner.class);
        when(owner.ledgerPath()).thenReturn(ledger);
        var bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            new FinancialSyncCommands(List.of(owner))
                    .execute("show-financial-daily-queue", Map.of());
        } finally {
            System.setOut(original);
        }
        var result = JobDefinitionJson.mapper().readTree(bytes.toByteArray());
        assertEquals("2026-10-20", result.path("logicalDate").asText());
        assertEquals("2026-10-20", result.path("allowance").path("day").asText());
        assertEquals("2026-06-30", result.path("nextSlice").path("period").asText());
        assertEquals("PENDING", result.path("status").asText());
        assertEquals(1, result.path("completed").asInt());
    }
}
