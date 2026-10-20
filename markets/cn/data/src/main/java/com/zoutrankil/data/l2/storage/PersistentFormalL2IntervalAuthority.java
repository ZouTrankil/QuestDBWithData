package com.zoutrankil.data.l2.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.l2.port.FormalL2IntervalAuthority;
import com.zoutrankil.data.repository.SqliteIntervalLockStore;
import com.zoutrankil.data.repository.SyncRunLedger;

import java.nio.file.Path;
import java.util.Map;

/** Lazy SQLite assembly keeps read validation and application startup free of ledger writes. */
public final class PersistentFormalL2IntervalAuthority implements FormalL2IntervalAuthority {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path path;
    private SyncRunLedger ledger;
    private SqliteIntervalLockStore delegate;

    public PersistentFormalL2IntervalAuthority(Path path) {
        this.path = path;
    }

    private synchronized void initialize() {
        if (ledger != null) return;
        try {
            var opened = new SyncRunLedger(path);
            var initializedLocks = new SqliteIntervalLockStore(path);
            ledger = opened;
            delegate = initializedLocks;
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Cannot initialize formal L2 interval authority", failure);
        }
    }

    @Override
    public Lease acquire(String runId, Scope scope) {
        return acquire(runId, scope, "java-parquet-1.17.1");
    }

    @Override
    public Lease acquire(String runId, Scope scope, String readerVersion) {
        initialize();
        try {
            String request =
                    JSON.writeValueAsString(
                            Map.of(
                                    "day",
                                    scope.from().toString(),
                                    "targetTable",
                                    "l2_daily_features",
                                    "reader",
                                    readerVersion,
                                    "certifiesExistingHistory",
                                    false));
            ledger.createRun(
                    new SyncRunLedger.Run(
                            runId,
                            null,
                            "data.l2_daily_features.formal",
                            1,
                            scope.from().toString(),
                            "l2_daily_features",
                            request));
            ledger.transition(runId, 0, SyncRunState.RUNNING, request);
            var lease = delegate.acquire(runId, scope);
            if (lease == null)
                ledger.transition(
                        runId, 1, SyncRunState.FAILED, "{\"errorCode\":\"DATASET_INTERVAL_BUSY\"}");
            return lease;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot acquire formal L2 date authority", failure);
        }
    }

    @Override
    public void finish(Lease lease, Map<String, Object> evidence, boolean submitted)
            throws Exception {
        if (evidence == null) {
            ledger.transition(
                    lease.runId(),
                    1,
                    submitted ? SyncRunState.IN_DOUBT : SyncRunState.FAILED,
                    "{\"errorCode\":\"NATIVE_L2_VALIDATION_INCOMPLETE\"}");
        } else
            ledger.transition(
                    lease.runId(), 1, SyncRunState.VERIFIED, JSON.writeValueAsString(evidence));
    }

    @Override
    public Lease findOwned(String runId, Scope scope) {
        initialize();
        return delegate.findOwned(runId, scope);
    }

    @Override
    public void retainInDoubt(Lease lease) {
        delegate.retainInDoubt(lease);
    }

    @Override
    public void releaseVerified(Lease lease) {
        delegate.releaseVerified(lease);
    }

    @Override
    public void releaseAfterReconciliation(
            Lease lease, boolean writerStopped, boolean exactReadback) {
        initialize();
        delegate.releaseAfterReconciliation(lease, writerStopped, exactReadback);
    }
}
