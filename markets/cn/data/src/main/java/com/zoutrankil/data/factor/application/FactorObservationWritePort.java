package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

/** A physical, attempt-scoped observation destination. */
public interface FactorObservationWritePort
        extends VerifiedWriteSession<GlobalFactorObservation, GlobalObservationCoverage.Key> {
    /** Recheck the connected endpoint/table and return its pinned identity. */
    String targetIdentity();
}
