package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.IntervalLockStore;
import com.zoutrankil.data.repository.SqliteIntervalLockStore;
import java.nio.file.Path;

/** 持久化日期区间锁存储的兼容应用接口。 */
public final class DatasetIntervalLock implements IntervalLockStore {
    private final IntervalLockStore store;

    public DatasetIntervalLock(Path ledgerPath) {
        store = new SqliteIntervalLockStore(ledgerPath);
    }

    @Override public Lease acquire(String runId, Scope scope) {
        return store.acquire(runId, scope);
    }
    @Override public Lease findOwned(String runId, Scope scope) {
        return store.findOwned(runId, scope);
    }
    @Override public void retainInDoubt(Lease lease) {
        store.retainInDoubt(lease);
    }
    @Override public void releaseVerified(Lease lease) {
        store.releaseVerified(lease);
    }
    @Override public void releaseAfterReconciliation(Lease lease, boolean writerStopped, boolean exactReadback) {
        store.releaseAfterReconciliation(lease, writerStopped, exactReadback);
    }
}
