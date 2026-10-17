package com.zoutrankil.data.client;

import java.time.Duration;
import java.util.*;

/** 全局与接口级限流原子判断；等待或被拒绝的预留不消耗配额。 */
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
    /** 返回 0 表示立即放行；正数表示需等待的单调时钟纳秒数。 */
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
    /** 持久化准入较慢时，也不能缩短两次实际发起网络请求之间的间隔。 */
    public synchronized void admissionReady(String endpoint, long now) {
        globalNext = now + globalSpacing;
        endpointNext.put(endpoint, now + spacing.getOrDefault(endpoint, defaultSpacing));
    }
}
