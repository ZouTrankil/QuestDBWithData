package com.zoutrankil.questdbwithdata.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SyncGroupDefinitionTest {
    private static SyncJobDefinition.JobRef ref(String id) { return new SyncJobDefinition.JobRef(id, 1); }
    private static SyncGroupDefinition.Member member(String id, String... after) {
        return new SyncGroupDefinition.Member(ref(id),
                java.util.Arrays.stream(after).map(SyncGroupDefinitionTest::ref).toList());
    }

    @Test void repeatedMemberIsDeduplicatedAndIndependentPeersKeepOrder() {
        var group = new SyncGroupDefinition("group.test", 1,
                List.of(member("job.a"), member("job.b"), member("job.a"), member("job.c")), true, false);
        assertEquals(List.of("job.a", "job.b", "job.c"),
                group.orderedMembers().stream().map(m -> m.job().jobId()).toList());
    }

    @Test void dependencyOrdersLaterNamedPrerequisiteFirst() {
        var group = new SyncGroupDefinition("group.test", 1,
                List.of(member("job.dependent", "job.base"), member("job.base")), true, false);
        assertEquals(List.of("job.base", "job.dependent"),
                group.orderedMembers().stream().map(m -> m.job().jobId()).toList());
    }

    @Test void unknownCycleAndConflictingDuplicatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SyncGroupDefinition("group.test", 1,
                List.of(member("job.a", "job.missing")), true, false));
        assertThrows(IllegalArgumentException.class, () -> new SyncGroupDefinition("group.test", 1,
                List.of(member("job.a", "job.b"), member("job.b", "job.a")), true, false));
        assertThrows(IllegalArgumentException.class, () -> new SyncGroupDefinition("group.test", 1,
                List.of(member("job.a"), member("job.a", "job.b"), member("job.b")), true, false));
    }

    @Test void newlyReadyEarlierMemberPrecedesLaterIndependentPeer() {
        var group = new SyncGroupDefinition("group.test", 1,
                List.of(member("job.a", "job.b"), member("job.b"), member("job.c")), true, false);
        assertEquals(List.of("job.b", "job.a", "job.c"),
                group.orderedMembers().stream().map(m -> m.job().jobId()).toList());
    }
}
