package com.zoutrankil.batch.source;

import java.util.Objects;

/** Immutable source behavior; collection state belongs to a newly opened session. */
public record SourceStrategy(
        SourceRequestPolicy request,
        SourceRowPolicy rows,
        SourceCoveragePolicy coverage,
        SourceStoragePolicy storage) {
    public SourceStrategy {
        Objects.requireNonNull(request);
        Objects.requireNonNull(rows);
        Objects.requireNonNull(coverage);
        Objects.requireNonNull(storage);
    }
}
