package com.zoutrankil.questdbwithdata.client;

import java.time.Duration;
import java.util.*;

/** Atomic global and endpoint pacing; rejected/waiting reservations never consume quota. */
public final class PacedQuota {
    private final long globalSpacing;
    private final long defaultSpacing;
    private final Map<String, Long> spacing;
    private Long globalNext;
    private final Map<String, Long> endpointNext = new HashMap<>();

    public PacedQuota(int globalPerMinute, int defaultEndpointPerMinute, Map<String, Integer> endpointLimits) {
        globalSpacing = spacing(globalPerMinute);
        defaultSpacing = spacing(defaultEndpointPerMinute);
        var copy = new HashMap<String, Long>();
        endpointLimits.forEach((key, value) -> copy.put(key, spacing(value)));
        spacing = Map.copyOf(copy);
    }
    private static long spacing(int requests) {
        if (requests < 1 || requests > 60_000) throw new IllegalArgumentException("Rate must be 1..60000/minute");
        return Math.ceilDiv(Duration.ofMinutes(1).toNanos(), requests);
    }
    /** Zero means admitted now; positive means retry after that many monotonic nanoseconds. */
    public synchronized long tryAcquire(String endpoint, long now) {
        long delay = Math.max(globalNext == null ? 0 : Math.max(0, globalNext - now),
                Math.max(0, endpointNext.getOrDefault(endpoint, now) - now));
        if (delay > 0) return delay;
        globalNext = now + globalSpacing;
        endpointNext.put(endpoint, now + spacing.getOrDefault(endpoint, defaultSpacing));
        return 0;
    }
    public long maximumSpacingNanos() {
        return Math.max(Math.max(globalSpacing, defaultSpacing), spacing.values().stream().mapToLong(Long::longValue).max().orElse(0));
    }
    /** Slow durable admission work must not shorten the interval between transport subscriptions. */
    public synchronized void admissionReady(String endpoint, long now) {
        globalNext = now + globalSpacing;
        endpointNext.put(endpoint, now + spacing.getOrDefault(endpoint, defaultSpacing));
    }
}
