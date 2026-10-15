package com.zoutrankil.data.l2.port;

import com.zoutrankil.data.domain.IntervalLockStore;
import java.util.Map;

/** Durable run completion paired with the shared formal business-date lease. */
public interface FormalL2IntervalAuthority extends IntervalLockStore {
    void finish(Lease lease, Map<String, Object> verificationEvidence, boolean submitted) throws Exception;
}
