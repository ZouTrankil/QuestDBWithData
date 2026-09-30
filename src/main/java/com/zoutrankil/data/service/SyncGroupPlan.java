package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncGroupDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition;
import java.time.LocalDate;
import java.util.*;

/** Validates every child before execution; the resulting requests cannot change mid-run. */
public final class SyncGroupPlan {
    /** An explicit empty window clears an inherited range for a snapshot member. */
    public record Window(LocalDate from, LocalDate to) {
        public Window {
            if ((from == null) != (to == null) || (from != null && from.isAfter(to)))
                throw new IllegalArgumentException("Complete ordered group window required");
        }
    }
    public record Override(SyncJobDefinition.Mode mode, Window window, Map<String, ?> parameters) {
        public Override { parameters = Map.copyOf(parameters); }
    }
    private final SyncGroupDefinition definition;
    private final LocalDate logicalDate;
    private final List<SyncJobDefinition.FrozenRequest> requests;

    private SyncGroupPlan(SyncGroupDefinition definition, LocalDate logicalDate,
                          List<SyncJobDefinition.FrozenRequest> requests) {
        this.definition = definition;
        this.logicalDate = logicalDate;
        this.requests = List.copyOf(requests);
    }

    public static SyncGroupPlan prepare(SyncGroupRegistry groups, SyncJobRegistry jobs,
            String id, int version, LocalDate logicalDate, SyncJobDefinition.Mode mode,
            Window window, Map<String, ?> commonParameters, Map<String, Override> overrides) {
        Objects.requireNonNull(logicalDate, "Frozen logical date required");
        Objects.requireNonNull(commonParameters);
        Objects.requireNonNull(overrides);
        var group = groups.require(id, version);
        if (!group.enabled()) throw new IllegalArgumentException("Group is disabled");
        var memberIds = new HashSet<String>();
        group.members().forEach(m -> memberIds.add(m.job().jobId()));
        if (!memberIds.containsAll(overrides.keySet()))
            throw new IllegalArgumentException("Override names a job outside the group");
        var requests = new ArrayList<SyncJobDefinition.FrozenRequest>();
        var prepared = new HashSet<SyncJobDefinition.JobRef>();
        for (var member : group.orderedMembers()) {
            var override = overrides.get(member.job().jobId());
            if (overrides.containsKey(member.job().jobId()) && override == null)
                throw new IllegalArgumentException("Null member override");
            var parameters = new LinkedHashMap<String, Object>(commonParameters);
            if (override != null) parameters.putAll(override.parameters());
            var effectiveMode = override != null && override.mode() != null ? override.mode() : mode;
            var effectiveWindow = override != null && override.window() != null ? override.window() : window;
            var request = jobs.prepare(member.job().jobId(), member.job().version(), effectiveMode,
                    parameters, effectiveWindow == null ? null : effectiveWindow.from(),
                    effectiveWindow == null ? null : effectiveWindow.to(), logicalDate);
            if (group.dailyEligible() && !request.definition().dailyEligible())
                throw new IllegalArgumentException("Daily group includes a non-daily job");
            if (!prepared.containsAll(request.definition().dependencies()))
                throw new IllegalArgumentException("Prepared catalog omits or misorders a dependency");
            prepared.add(member.job());
            requests.add(request);
        }
        return new SyncGroupPlan(group, logicalDate, requests);
    }

    public SyncGroupDefinition definition() { return definition; }
    public LocalDate logicalDate() { return logicalDate; }
    public List<SyncJobDefinition.FrozenRequest> requests() { return requests; }
    public boolean failFast() { return true; }
    public int maxConcurrency() { return 1; }
}
