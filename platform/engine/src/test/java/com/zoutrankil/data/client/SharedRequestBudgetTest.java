package com.zoutrankil.data.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.client.TushareFailure.Kind.*;

class SharedRequestBudgetTest {
    @TempDir Path directory;
    private SharedRequestBudget.Policy policy(int attempts, int concurrency, int queue, Duration timeout) {
        return new SharedRequestBudget.Policy(60000, 60000, Map.of(), concurrency, queue, attempts,
                timeout, Duration.ofMillis(5), Duration.ofMillis(20), Set.of());
    }
    @Test void fakeClockConcurrentEndpointsCannotExceedGlobalOrEndpointQuota() throws Exception {
        var clock = new AtomicLong();
        var quota = new PacedQuota(20, 10, Map.of("slow", 1));
        var granted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 100; i++) jobs.add(executor.submit(() -> {
                if (quota.tryAcquire("slow", clock.get()) == 0) granted.incrementAndGet();
            }));
            for (var job : jobs) job.get();
        }
        assertEquals(1, granted.get());
        clock.set(Duration.ofSeconds(3).toNanos());
        assertEquals(Duration.ofSeconds(57).toNanos(), quota.tryAcquire("slow", clock.get()));
        assertEquals(0, quota.tryAcquire("other", clock.get()));
        assertEquals(Duration.ofSeconds(3).toNanos(), quota.tryAcquire("third", clock.get()));
        clock.set(Duration.ofSeconds(60).toNanos());
        assertEquals(0, quota.tryAcquire("slow", clock.get()));
    }

    @Test void retriesAreChargedWhilePermissionAndContractFailuresAreNotRetried() throws Exception {
        try (var budget = new SharedRequestBudget(policy(3, 2, 4, Duration.ofSeconds(3)), directory)) {
            var calls = new AtomicInteger();
            assertEquals("ok", budget.execute("a", "secret", () -> calls.incrementAndGet() < 3
                    ? Mono.error(new TushareFailure(HTTP, 429, "rate")) : Mono.just("ok")).block());
            assertEquals(3, calls.get());
            assertEquals(List.of(1, 2, 3), budget.observations().stream().map(SharedRequestBudget.Attempt::attempt).toList());
            for (var kind : List.of(BUSINESS, CONTRACT)) {
                calls.set(0);
                assertThrows(RuntimeException.class, () -> budget.execute("a", "secret", () -> {
                    calls.incrementAndGet();
                    return Mono.error(new TushareFailure(kind, -2002, "denied"));
                }).block());
                assertEquals(1, calls.get());
            }
            calls.set(0);
            assertEquals("ok", budget.execute("b", "secret", () -> calls.incrementAndGet() == 1
                    ? Mono.error(new TushareFailure(BUSINESS_RATE_LIMIT, -2001, "rate")) : Mono.just("ok")).block());
            assertEquals(2, calls.get());
        }
    }

    @Test void boundedQueueCancellationAndCloseReleaseInFlightWork() throws Exception {
        var budget = new SharedRequestBudget(policy(1, 1, 1, Duration.ofSeconds(5)), directory);
        var started = new CountDownLatch(1);
        var canceled = new CountDownLatch(1);
        try {
            var first = budget.execute("a", "secret", () -> Mono.<String>never()
                    .doOnSubscribe(ignored -> started.countDown()).doOnCancel(canceled::countDown)).toFuture();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            var calls = new AtomicInteger();
            var waiting = budget.execute("b", "secret", () -> { calls.incrementAndGet(); return Mono.just("bad"); }).toFuture();
            assertThrows(ExecutionException.class, () -> budget.execute("c", "secret", () -> Mono.just("bad"))
                    .toFuture().get(1, TimeUnit.SECONDS));
            assertTrue(waiting.cancel(true));
            assertEquals(0, calls.get());
            budget.close();
            assertTrue(canceled.await(1, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> first.get(1, TimeUnit.SECONDS));
        } finally { budget.close(); }
    }

    @Test void credentialLockPreventsSecondOwnerAndSurvivesRestart() throws Exception {
        var slow = new SharedRequestBudget.Policy(300, 300, Map.of(), 1, 1, 1,
                Duration.ofSeconds(2), Duration.ZERO, Duration.ZERO, Set.of());
        long firstTime;
        try (var first = new SharedRequestBudget(slow, directory);
             var second = new SharedRequestBudget(slow, directory)) {
            assertEquals(1, first.execute("a", "secret", () -> Mono.just(1)).block());
            firstTime = first.observations().getFirst().startedAt().toEpochMilli();
            assertThrows(RuntimeException.class, () -> second.execute("a", "secret", () -> Mono.just(1)).block());
        }
        try (var restarted = new SharedRequestBudget(slow, directory)) {
            assertEquals(1, restarted.execute("a", "secret", () -> Mono.just(1)).block());
            assertTrue(restarted.observations().getFirst().startedAt().toEpochMilli() - firstTime >= 180);
        }
    }

    @Test void attemptCountAndTotalDeadlineBoundRepeatedFailures() throws Exception {
        try (var budget = new SharedRequestBudget(policy(3, 1, 2, Duration.ofSeconds(1)), directory)) {
            var calls = new AtomicInteger();
            assertThrows(RuntimeException.class, () -> budget.execute("a", "secret", () -> {
                calls.incrementAndGet();
                return Mono.error(new TushareFailure(HTTP, 503, "unavailable"));
            }).block());
            assertEquals(3, calls.get());
        }
        try (var budget = new SharedRequestBudget(policy(3, 1, 2, Duration.ofMillis(100)), directory)) {
            var canceled = new CountDownLatch(1);
            assertThrows(RuntimeException.class, () -> budget.execute("a", "secret",
                    () -> Mono.never().doOnCancel(canceled::countDown)).block());
            assertTrue(canceled.await(1, TimeUnit.SECONDS));
        }
    }
}
