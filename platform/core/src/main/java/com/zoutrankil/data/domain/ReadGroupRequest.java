package com.zoutrankil.data.domain;

import java.time.Duration;
import java.util.*;

/** One explicit bounded page per member; callers advance each cursor independently. */
public record ReadGroupRequest(List<Member> members, Duration timeout) {
    public record Member(String memberId, String datasetId, int definitionVersion, DatasetReadQuery query) {
        public Member {
            SyncJobDefinition.name(memberId); SyncJobDefinition.name(datasetId);
            if (definitionVersion < 1) throw new IllegalArgumentException("Positive dataset version required");
            Objects.requireNonNull(query);
        }
    }
    public ReadGroupRequest {
        members = List.copyOf(members);
        if (members.isEmpty() || members.size() > 64)
            throw new IllegalArgumentException("Read group requires 1..64 explicit members");
        var ids = new HashSet<String>();
        long rows = 0;
        for (var member : members) {
            if (!ids.add(member.memberId())) throw new IllegalArgumentException("Duplicate read member ID");
            rows += member.query().pageSize();
        }
        if (rows > 100_000) throw new IllegalArgumentException("Combined read page budget exceeds 100000 rows");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("Read group timeout must be positive and at most ten minutes");
    }
}
