package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.*;

/** Explicit full rows, stable batch identities, and one member per dataset. */
public record WriteGroupRequest(String batchId, LocalDate logicalDate, List<Member> members) {
    public record Member(String memberId, String datasetId, int definitionVersion,
                         String batchId, List<DatasetValues> rows) {
        public Member {
            SyncJobDefinition.name(memberId); SyncJobDefinition.name(datasetId); SyncJobDefinition.name(batchId);
            if (definitionVersion < 1) throw new IllegalArgumentException("Positive dataset version required");
            rows = List.copyOf(rows);
            if (rows.size() > 10_000) throw new IllegalArgumentException("Write member exceeds 10000 rows");
        }
    }
    public WriteGroupRequest {
        SyncJobDefinition.name(batchId); Objects.requireNonNull(logicalDate);
        members = List.copyOf(members);
        if (members.isEmpty() || members.size() > 64) throw new IllegalArgumentException("Write group requires 1..64 members");
        var ids = new HashSet<String>(); var datasets = new HashSet<String>(); long rows = 0;
        for (var member : members) {
            if (!ids.add(member.memberId()) || !datasets.add(member.datasetId()))
                throw new IllegalArgumentException("Each member and dataset must occur once; combine its rows explicitly");
            rows += member.rows().size();
        }
        if (rows > 100_000) throw new IllegalArgumentException("Write group exceeds 100000 total rows");
    }
}
