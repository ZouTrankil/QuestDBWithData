package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncGroupDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition;
import java.util.*;

/** Validates group membership against the single-job catalog before any run starts. */
public final class SyncGroupRegistry {
    private final Map<String, SyncGroupDefinition> groups;
    private final SyncJobRegistry jobs;

    public SyncGroupRegistry(Collection<SyncGroupDefinition> definitions, SyncJobRegistry jobs) {
        this.jobs = Objects.requireNonNull(jobs);
        var found = new LinkedHashMap<String, SyncGroupDefinition>();
        for (var group : definitions) {
            Objects.requireNonNull(group);
            if (found.putIfAbsent(group.groupId(), group) != null)
                throw new IllegalArgumentException("Duplicate group ID");
            var seen = new HashSet<SyncJobDefinition.JobRef>();
            for (var member : group.orderedMembers()) {
                var definition = jobs.require(member.job().jobId(), member.job().version());
                if (!seen.containsAll(definition.dependencies()))
                    throw new IllegalArgumentException("Group omits or misorders a job dependency");
                seen.add(member.job());
            }
        }
        groups = Collections.unmodifiableMap(found);
    }

    public SyncGroupDefinition require(String id, int version) {
        var group = groups.get(id);
        if (group == null || group.version() != version)
            throw new IllegalArgumentException("Unknown group or version");
        return group;
    }
    public List<SyncGroupDefinition> definitions() { return List.copyOf(groups.values()); }
    public List<SyncJobDefinition> allEnabledJobs() {
        return jobs.definitions().stream().filter(SyncJobDefinition::enabled).toList();
    }
    public List<SyncJobDefinition> dailyEligibleJobs() {
        return jobs.definitions().stream().filter(j -> j.enabled() && j.dailyEligible()).toList();
    }
}
