package com.zoutrankil.data.marketdata;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class MarketPublisherLeaseTest {
    @TempDir Path root;

    @Test
    void separatePublishersCannotUpgradeTheSameLedgerConcurrently() throws Exception {
        var first =
                MarketPublisherLease.acquire(root.resolve("writer.lock"), Duration.ofSeconds(1));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var started = new CountDownLatch(1);
            var future =
                    executor.submit(
                            () -> {
                                started.countDown();
                                try (var lease =
                                        MarketPublisherLease.acquire(
                                                root.resolve("writer.lock"),
                                                Duration.ofSeconds(3))) {
                                    return true;
                                }
                            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> future.get(150, TimeUnit.MILLISECONDS));
            first.close();
            assertTrue(future.get(3, TimeUnit.SECONDS));
        }
    }
}
