package com.zoutrankil.batch;

import java.util.Objects;

/** Immutable source behavior; collection state belongs to a newly opened session. */
record SourceStrategy(SourceRequestPolicy request, SourceRowPolicy rows,
                      SourceCoveragePolicy coverage, SourceStoragePolicy storage) {
    SourceStrategy {
        Objects.requireNonNull(request); Objects.requireNonNull(rows);
        Objects.requireNonNull(coverage); Objects.requireNonNull(storage);
    }
}
