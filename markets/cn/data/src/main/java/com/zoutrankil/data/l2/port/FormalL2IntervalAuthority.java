package com.zoutrankil.data.l2.port;

import com.zoutrankil.data.domain.IntervalLockStore;

import java.util.Map;

/** Durable run completion paired with the shared formal business-date lease. */
public interface FormalL2IntervalAuthority extends IntervalLockStore {
    default Lease acquire(String runId, Scope scope, String readerVersion) {
        return acquire(runId, scope);
    }

    void finish(Lease lease, Map<String, Object> verificationEvidence, boolean submitted)
            throws Exception;
}
