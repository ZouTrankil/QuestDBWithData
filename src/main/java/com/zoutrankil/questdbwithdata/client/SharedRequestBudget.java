package com.zoutrankil.questdbwithdata.client;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static com.zoutrankil.questdbwithdata.client.TushareFailure.Kind.*;

/** One credential/provider budget with a local process lease. Every retry reacquires both quotas. */
public final class SharedRequestBudget implements AutoCloseable {
    public record Policy(int globalPerMinute, int endpointPerMinute, Map<String, Integer> endpointLimits,
                         int concurrency, int queueCapacity, int maxAttempts, Duration totalTimeout,
                         Duration retryBase, Duration retryMax, Set<Integer> retryableBusinessCodes) {
        public Policy {
            endpointLimits = Map.copyOf(endpointLimits);
            retryableBusinessCodes = Set.copyOf(retryableBusinessCodes);
            new PacedQuota(globalPerMinute, endpointPerMinute, endpointLimits);
            if (concurrency < 1 || concurrency > 64 || queueCapacity < 0 || queueCapacity > 10000
                    || maxAttempts < 1 || maxAttempts > 10 || totalTimeout.isNegative() || totalTimeout.isZero()
                    || totalTimeout.compareTo(Duration.ofHours(1)) > 0 || retryBase.isNegative()
                    || retryMax.compareTo(retryBase) < 0 || retryMax.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new IllegalArgumentException("Invalid request budget policy");
            }
        }
    }
    public record Attempt(String endpoint, Instant startedAt, int attempt) {}
    private final Policy policy;
    private final PacedQuota quota;
    private final Path leaseDirectory;
    private final AtomicInteger admitted = new AtomicInteger();
    private final ArrayDeque<Attempt> evidence = new ArrayDeque<>();
    private int inFlight;
    private String credentialHash;
    private FileChannel channel;
    private FileLock lease;
    private long restartWaitUntil;
    private boolean closed;
    private final Sinks.One<Void> shutdown = Sinks.one();

    public SharedRequestBudget(Policy policy, Path leaseDirectory) {
        this.policy = policy;
        this.leaseDirectory = leaseDirectory;
        quota = new PacedQuota(policy.globalPerMinute(), policy.endpointPerMinute(), policy.endpointLimits());
    }

    public <T> Mono<T> execute(String endpoint, String credential, Supplier<Mono<T>> transport) {
        return Mono.defer(() -> {
            try { ensureLease(credential); }
            catch (Exception failure) { return Mono.error(new TushareFailure(CONTRACT, null, "Credential budget lease unavailable; another instance may own it")); }
            if (admitted.incrementAndGet() > policy.concurrency() + policy.queueCapacity()) {
                admitted.decrementAndGet();
                return Mono.error(new TushareFailure(CONTRACT, null, "Request budget queue is full"));
            }
            return Mono.firstWithSignal(attempt(endpoint, transport, 1), shutdown.asMono().then(
                            Mono.<T>error(new TushareFailure(CONTRACT, null, "Request budget shut down"))))
                    .timeout(policy.totalTimeout())
                    .onErrorMap(java.util.concurrent.TimeoutException.class,
                            ignored -> new TushareFailure(TRANSPORT, null, "Total request/retry budget deadline exceeded"))
                    .doFinally(ignored -> admitted.decrementAndGet());
        });
    }

    private <T> Mono<T> attempt(String endpoint, Supplier<Mono<T>> transport, int number) {
        var acquired = new AtomicInteger();
        return acquire(endpoint, acquired).then(Mono.defer(() -> {
                    synchronized (this) {
                        if (evidence.size() == 200) evidence.removeFirst();
                        evidence.addLast(new Attempt(endpoint, Instant.now(), number));
                    }
                    return transport.get();
                }))
                .doFinally(ignored -> {
                    if (acquired.getAndSet(2) == 1) synchronized (this) { inFlight--; }
                })
                .onErrorResume(error -> {
                    if (number >= policy.maxAttempts() || !retryable(error)) return Mono.error(error);
                    long cap = Math.min(policy.retryMax().toMillis(),
                            Math.multiplyExact(policy.retryBase().toMillis(), 1L << (number - 1)));
                    long jitter = cap == 0 ? 0 : ThreadLocalRandom.current().nextLong(cap / 2, cap + 1);
                    if (error instanceof TushareFailure failure && failure.retryAfter() != null) {
                        jitter = Math.max(jitter, failure.retryAfter().toMillis());
                    }
                    return Mono.delay(Duration.ofMillis(jitter)).then(Mono.defer(() -> attempt(endpoint, transport, number + 1)));
                });
    }

    private Mono<Void> acquire(String endpoint, AtomicInteger acquired) {
        return Mono.defer(() -> {
            long delay;
            synchronized (this) {
                if (closed) return Mono.error(new TushareFailure(CONTRACT, null, "Request budget closed"));
                if (acquired.get() == 2) return Mono.empty();
                delay = Math.max(0, restartWaitUntil - System.nanoTime());
                if (delay == 0 && inFlight >= policy.concurrency()) delay = Duration.ofMillis(25).toNanos();
                if (delay == 0) delay = quota.tryAcquire(endpoint, System.nanoTime());
                if (delay == 0) {
                    try {
                        // Crash/restart cannot reset quota. Conservatively cool down all endpoints.
                        byte[] next = Long.toString(System.currentTimeMillis()
                                + Math.ceilDiv(quota.maximumSpacingNanos(), 1_000_000L)).getBytes(StandardCharsets.UTF_8);
                        channel.position(0);
                        var bytes = ByteBuffer.wrap(next);
                        while (bytes.hasRemaining()) channel.write(bytes);
                        channel.truncate(next.length);
                        channel.force(true);
                        quota.admissionReady(endpoint, System.nanoTime());
                    } catch (Exception failure) {
                        return Mono.error(new TushareFailure(CONTRACT, null, "Cannot persist credential budget admission"));
                    }
                    if (acquired.compareAndSet(0, 1)) inFlight++;
                    return Mono.empty();
                }
            }
            return Mono.delay(Duration.ofNanos(delay)).then(Mono.defer(() -> acquire(endpoint, acquired)));
        });
    }

    private boolean retryable(Throwable error) {
        if (!(error instanceof TushareFailure failure)) return false;
        return failure.kind() == TRANSPORT
                || failure.kind() == BUSINESS_RATE_LIMIT
                || failure.kind() == HTTP && (failure.code() == 429 || failure.code() >= 500 && failure.code() <= 599)
                || failure.kind() == BUSINESS && policy.retryableBusinessCodes().contains(failure.code());
    }

    private synchronized void ensureLease(String credential) throws Exception {
        if (closed || credential == null || credential.isBlank()) throw new IllegalStateException("No credential/closed");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("tushare:" + credential).getBytes(StandardCharsets.UTF_8)));
        if (credentialHash != null) {
            if (!credentialHash.equals(hash)) throw new IllegalStateException("Credential changed");
            return;
        }
        Files.createDirectories(leaseDirectory);
        var candidate = FileChannel.open(leaseDirectory.resolve(hash + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            var lock = candidate.tryLock();
            if (lock == null) throw new IllegalStateException("Credential already owned");
            var buffer = ByteBuffer.allocate(64);
            candidate.read(buffer);
            buffer.flip();
            if (buffer.hasRemaining()) {
                long saved = Long.parseLong(StandardCharsets.UTF_8.decode(buffer).toString());
                long wait = Math.max(0, saved - System.currentTimeMillis());
                restartWaitUntil = System.nanoTime() + Duration.ofMillis(wait).toNanos();
            } else restartWaitUntil = System.nanoTime();
            channel = candidate;
            lease = lock;
            credentialHash = hash;
        } catch (Exception failure) {
            candidate.close();
            throw failure;
        }
    }

    public synchronized List<Attempt> observations() { return List.copyOf(evidence); }
    public Policy policy() { return policy; }
    @Override public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        shutdown.tryEmitEmpty();
        if (lease != null) lease.release();
        if (channel != null) channel.close();
    }
}
