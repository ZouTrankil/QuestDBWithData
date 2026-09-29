package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.SyncScheduleStore;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;

/** Explicit tick only; no background polling or automatic data writes. */
public final class SyncScheduleManager {
    @FunctionalInterface public interface Dispatcher {
        RunResult run(SyncScheduleDefinition definition, SyncScheduleDefinition.Slot slot) throws Exception;
    }
    public record RunResult(String runId, SyncRunState state) {
        public RunResult { Objects.requireNonNull(runId); Objects.requireNonNull(state); }
    }
    public record Status(SyncScheduleDefinition definition, SyncScheduleDefinition.Slot next,
                         List<SyncScheduleStore.History> history) {}
    private final SyncScheduleStore store;
    private final SyncJobRegistry jobs;
    private final SyncGroupRegistry groups;
    private final Predicate<LocalDate> exchangeSession;
    private final Clock clock;
    public SyncScheduleManager(SyncScheduleStore store, SyncJobRegistry jobs, SyncGroupRegistry groups,
                               Predicate<LocalDate> exchangeSession, Clock clock) {
        this.store=Objects.requireNonNull(store); this.jobs=Objects.requireNonNull(jobs);
        this.groups=Objects.requireNonNull(groups); this.exchangeSession=Objects.requireNonNull(exchangeSession);
        this.clock=Objects.requireNonNull(clock);
    }
    public void put(SyncScheduleDefinition definition) throws Exception {
        if (definition.target() == SyncScheduleDefinition.Target.JOB)
            jobs.require(definition.targetId(),definition.targetVersion());
        else groups.require(definition.targetId(),definition.targetVersion());
        store.put(definition);
    }
    public void setEnabled(String id, boolean enabled) throws Exception {
        var d=store.get(id);
        put(d.withEnabled(enabled));
    }
    public Status status(String id) throws Exception {
        var d=store.get(id);
        var next=d.nextAfter(clock.instant(),exchangeSession).orElse(null);
        return new Status(d,next,store.history(id,100));
    }
    public List<SyncScheduleStore.History> tick(Dispatcher dispatcher) throws Exception {
        Objects.requireNonNull(dispatcher);
        Instant now=clock.instant();
        var emitted=new ArrayList<SyncScheduleStore.History>();
        for (var definition:store.list()) {
            if (!definition.enabled()) continue;
            Duration window=definition.maxLateness();
            Duration lookback=window.compareTo(Duration.ofDays(1)) > 0 ? window : Duration.ofDays(1);
            Instant start=now.minus(lookback).minusNanos(1);
            SyncScheduleDefinition.Slot latest=null;
            // At most one dispatch per schedule per tick, even after a long host outage.
            // Seven days is the maximum catch-up window and one minute the minimum interval.
            // Include both boundary slots; a daily-only bound would truncate interval schedules.
            for (int i=0;i<7*24*60+2;i++) {
                var next=definition.nextAfter(start,exchangeSession);
                if (next.isEmpty() || next.get().dueAt().isAfter(now)) break;
                latest=next.get(); start=latest.dueAt();
            }
            if (latest==null) continue;
            Duration late=Duration.between(latest.dueAt(),now);
            boolean missed=late.compareTo(window)>0
                    || definition.misfire()==SyncScheduleDefinition.Misfire.SKIP && !late.isZero();
            if (missed) {
                store.claim(definition.scheduleId(),latest.dueAt(),SyncScheduleStore.State.MISSED,"misfire-policy");
            } else if (store.claim(definition.scheduleId(),latest.dueAt(),SyncScheduleStore.State.CLAIMED,"dispatch-started")) {
                try {
                    var result=dispatcher.run(definition,latest);
                    store.finish(definition.scheduleId(),latest.dueAt(),state(result.state()),result.runId(),
                            "same-runner-result");
                } catch (Exception uncertain) {
                    // An exception cannot distinguish no send from an unknown write; no automatic retry.
                    store.finish(definition.scheduleId(),latest.dueAt(),SyncScheduleStore.State.IN_DOUBT,null,
                            "dispatcher-exception:"+uncertain.getClass().getSimpleName());
                }
            }
            emitted.addAll(store.history(definition.scheduleId(),1));
        }
        return List.copyOf(emitted);
    }
    private static SyncScheduleStore.State state(SyncRunState state) {
        return switch (state) {
            case VERIFIED -> SyncScheduleStore.State.VERIFIED;
            case VERIFIED_EMPTY -> SyncScheduleStore.State.VERIFIED_EMPTY;
            case PARTIAL -> SyncScheduleStore.State.PARTIAL;
            case FAILED -> SyncScheduleStore.State.FAILED;
            case CANCELLED -> SyncScheduleStore.State.CANCELLED;
            default -> SyncScheduleStore.State.IN_DOUBT;
        };
    }
}
