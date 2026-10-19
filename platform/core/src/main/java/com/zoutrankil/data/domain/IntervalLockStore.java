package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Persistent exclusion for one dataset and an inclusive business interval. */
public interface IntervalLockStore {
    record Scope(String datasetId, LocalDate from, LocalDate to) {
        public Scope {
            if (datasetId == null || !datasetId.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}"))
                throw new IllegalArgumentException("Valid dataset ID required");
            Objects.requireNonNull(from); Objects.requireNonNull(to);
            if (to.isBefore(from)) throw new IllegalArgumentException("Reversed conflict interval");
        }
        public static Scope allDates(String datasetId) {
            return new Scope(datasetId, LocalDate.MIN, LocalDate.MAX);
        }
    }

    record Lease(String id, String runId, Scope scope, boolean inDoubt) {}

    /** Returns null on overlap. */
    Lease acquire(String runId, Scope scope);
    Lease findOwned(String runId, Scope scope);
    void retainInDoubt(Lease lease);
    void releaseVerified(Lease lease);
    void releaseAfterReconciliation(Lease lease, boolean writerStopped, boolean exactReadback);
}
