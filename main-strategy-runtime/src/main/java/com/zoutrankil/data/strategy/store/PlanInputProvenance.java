package com.zoutrankil.data.strategy.store;

import java.time.LocalDate;

/** Frozen score/recipe identities supplied by the verified input publisher. */
public record PlanInputProvenance(String scoreRunId, String scorePanelSha256,
        String scoreCoverageSha256, String recipeSha256, LocalDate maxCompleteDate,
        String recipeCodeRevision) {
    public PlanInputProvenance {
        if (scoreRunId == null || scoreRunId.isBlank())
            throw new PlanStoreException("official plan requires a score producer run");
        requireSha256(scorePanelSha256, "score panel");
        requireSha256(scoreCoverageSha256, "score coverage");
        requireSha256(recipeSha256, "recipe");
        if (maxCompleteDate == null) throw new PlanStoreException("official plan requires a complete score date");
        recipeCodeRevision = recipeCodeRevision == null ? "" : recipeCodeRevision;
    }

    public PlanInputProvenance(String scoreRunId, String panelSha256, String coverageSha256,
            String recipeSha256, LocalDate maxCompleteDate) {
        this(scoreRunId, panelSha256, coverageSha256, recipeSha256, maxCompleteDate, "");
    }

    static void requireSha256(String value, String description) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new PlanStoreException(description + " requires a lowercase SHA-256 identity");
    }
}
