package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class VerifiedBatchWalRecoveryTest {
    private final VerifiedBatchExecutor.Codec<String, String> codec =
            new VerifiedBatchExecutor.Codec<>() {
                public String key(String row) {
                    return row;
                }

                public byte[] canonicalBytes(String row) {
                    return row.getBytes(StandardCharsets.UTF_8);
                }
            };

    @Test
    void pendingWalWaitsThenVerifiesWithoutSendingAgain() {
        var sends = new AtomicInteger();
        var checks = new AtomicInteger();
        var reads = new AtomicInteger();
        var port =
                new VerifiedBatchExecutor.Port<String, String>() {
                    public void preflight() {}

                    public void send(List<String> rows) {
                        sends.incrementAndGet();
                    }

                    public List<String> readback(List<String> keys) {
                        reads.incrementAndGet();
                        return keys;
                    }

                    public boolean walSettled() {
                        return true;
                    }

                    public VerifiedBatchExecutor.WalState walState() {
                        return checks.incrementAndGet() < 3
                                ? VerifiedBatchExecutor.WalState.PENDING
                                : VerifiedBatchExecutor.WalState.SETTLED;
                    }
                };
        var result =
                new VerifiedBatchExecutor<>(
                                new VerifiedBatchExecutor.Policy(
                                        1, 100, 1, Duration.ofSeconds(1), Duration.ofMillis(1)),
                                codec,
                                port)
                        .execute(List.of("a").iterator());
        assertEquals(VerifiedBatchExecutor.Status.VERIFIED, result.status());
        assertEquals(1, sends.get());
        assertEquals(1, reads.get());
    }

    @Test
    void reverseWalStaysInDoubtAndDoesNotReadOrResend() {
        var sends = new AtomicInteger();
        var reads = new AtomicInteger();
        var port =
                new VerifiedBatchExecutor.Port<String, String>() {
                    public void preflight() {}

                    public void send(List<String> rows) {
                        sends.incrementAndGet();
                    }

                    public List<String> readback(List<String> keys) {
                        reads.incrementAndGet();
                        return keys;
                    }

                    public boolean walSettled() {
                        return false;
                    }

                    public VerifiedBatchExecutor.WalState walState() {
                        return VerifiedBatchExecutor.WalState.REVERSED;
                    }
                };
        var result =
                new VerifiedBatchExecutor<>(
                                new VerifiedBatchExecutor.Policy(
                                        1, 100, 1, Duration.ofMillis(10), Duration.ofMillis(1)),
                                codec,
                                port)
                        .execute(List.of("a").iterator());
        assertEquals(VerifiedBatchExecutor.Status.IN_DOUBT, result.status());
        assertTrue(result.reason().contains("wal_REVERSED"));
        assertEquals(1, sends.get());
        assertEquals(0, reads.get());
    }
}
