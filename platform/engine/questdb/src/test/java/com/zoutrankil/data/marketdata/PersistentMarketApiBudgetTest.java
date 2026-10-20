package com.zoutrankil.data.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

class PersistentMarketApiBudgetTest {
    @TempDir Path root;

    @Test
    void limitsSurviveReopenAndUnknownResponsesKeepTheirBandwidthReservation() throws Exception {
        var path = root.resolve("budget.db");
        var policy = Map.of("test", new PersistentMarketApiBudget.Policy(10, 20, 1, 100, 0));
        try (var budget = new PersistentMarketApiBudget(path, policy)) {
            var id = budget.reserve("test", "account", "A", 60);
            budget.complete(id, 0, 0, null);
        }
        try (var budget = new PersistentMarketApiBudget(path, policy)) {
            assertThrows(
                    PersistentMarketApiBudget.Exhausted.class,
                    () -> budget.reserve("test", "account", "A", 50));
            assertThrows(
                    PersistentMarketApiBudget.Exhausted.class,
                    () -> budget.reserve("test", "account", "B", 1));
            assertNotNull(budget.reserve("test", "other-account", "B", 50));
        }
    }

    @Test
    void rateLimitResponseBlocksAllSymbolsForTheSameAccount() throws Exception {
        try (var budget =
                new PersistentMarketApiBudget(
                        root.resolve("budget.db"),
                        Map.of(
                                "test",
                                new PersistentMarketApiBudget.Policy(10, 20, 10, 1000, 0)))) {
            var id = budget.reserve("test", "account", "A", 100);
            var retry = Instant.now().plusSeconds(90);
            budget.complete(id, 429, 12, retry);
            var blocked =
                    assertThrows(
                            PersistentMarketApiBudget.Exhausted.class,
                            () -> budget.reserve("test", "account", "B", 100));
            assertEquals(retry.toEpochMilli(), blocked.nextAllowed.toEpochMilli());
            assertEquals(0L, budget.usage().getFirst().get("reservedBytes"));
        }
    }
}
