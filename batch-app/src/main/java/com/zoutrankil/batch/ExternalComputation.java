package com.zoutrankil.batch;

import java.time.Instant;
import java.util.Objects;

/** No shell commands or production Python entry points are accepted here. */
public interface ExternalComputation {
    record Observation(String childId, boolean alive, Instant heartbeat, CompletionEvidence result, String reason) {}
    Observation observe(RunRequest request, String stage);

    static ExternalComputation disconnected() {
        return (request, stage) -> new Observation(null, false, null, null, "not-connected");
    }
    static BusinessState validate(RunRequest request, String stage, Observation observation) {
        Objects.requireNonNull(observation);
        if (observation.alive()) return BusinessState.RUNNING;
        if (observation.result() == null)
            if (observation.reason()!=null && observation.reason().startsWith("blocked:")) return BusinessState.BLOCKED;
        if (observation.result() == null)
            return observation.childId() == null ? BusinessState.BLOCKED : BusinessState.IN_DOUBT;
        if (!observation.result().matches(request, stage)) return BusinessState.BLOCKED;
        return observation.result().state();
    }
}
