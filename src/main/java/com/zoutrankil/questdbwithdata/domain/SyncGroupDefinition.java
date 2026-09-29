package com.zoutrankil.questdbwithdata.domain;

import java.util.*;

/** Versioned composition of existing single-dataset jobs; no connector logic lives here. */
public record SyncGroupDefinition(String groupId, int version, List<Member> members,
                                  boolean enabled, boolean dailyEligible) {
    public record Member(SyncJobDefinition.JobRef job, List<SyncJobDefinition.JobRef> after) {
        public Member {
            Objects.requireNonNull(job);
            after = List.copyOf(after);
            if (new HashSet<>(after).size() != after.size() || after.contains(job))
                throw new IllegalArgumentException("Duplicate or self group dependency");
        }
    }

    public SyncGroupDefinition {
        SyncJobDefinition.name(groupId);
        if (version < 1) throw new IllegalArgumentException("Positive group version required");
        if (dailyEligible && !enabled) throw new IllegalArgumentException("Disabled group cannot be daily eligible");
        var unique = new LinkedHashMap<String, Member>();
        for (var member : members) {
            Objects.requireNonNull(member);
            var previous = unique.putIfAbsent(member.job().jobId(), member);
            if (previous != null && !previous.equals(member))
                throw new IllegalArgumentException("Conflicting duplicate group member");
        }
        if (unique.isEmpty() || unique.size() > 1000)
            throw new IllegalArgumentException("Finite nonempty group required");
        members = List.copyOf(unique.values());
        for (var member : members) for (var dependency : member.after()) {
            var required = unique.get(dependency.jobId());
            if (required == null || !required.job().equals(dependency))
                throw new IllegalArgumentException("Unknown group dependency or version");
        }
        ordered(members); // Detect cycles when definition is frozen.
    }

    /** Stable topological order: explicit dependencies first, original order for peers. */
    public List<Member> orderedMembers() { return ordered(members); }

    private static List<Member> ordered(List<Member> members) {
        var remaining = new ArrayList<>(members);
        var complete = new HashSet<SyncJobDefinition.JobRef>();
        var result = new ArrayList<Member>();
        while (!remaining.isEmpty()) {
            boolean advanced = false;
            for (var iterator = remaining.iterator(); iterator.hasNext();) {
                var member = iterator.next();
                if (complete.containsAll(member.after())) {
                    result.add(member); complete.add(member.job()); iterator.remove(); advanced = true;
                    break; // Reconsider earlier members newly made ready before later peers.
                }
            }
            if (!advanced) throw new IllegalArgumentException("Group dependency cycle");
        }
        return List.copyOf(result);
    }
}
