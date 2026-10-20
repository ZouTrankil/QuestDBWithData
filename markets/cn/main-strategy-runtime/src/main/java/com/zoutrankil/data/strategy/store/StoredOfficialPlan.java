package com.zoutrankil.data.strategy.store;

import java.util.Optional;

public record StoredOfficialPlan(
        String planId,
        String contentSha256,
        String publishedAt,
        OfficialWeeklyPlan plan,
        boolean created,
        Optional<PlanInputProvenance> provenance,
        String sourceSnapshotPath,
        String sourceSha256,
        boolean semanticHashVerified) {
    public StoredOfficialPlan {
        provenance = provenance == null ? Optional.empty() : provenance;
    }
}
