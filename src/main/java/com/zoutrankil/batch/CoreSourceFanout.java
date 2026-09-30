package com.zoutrankil.batch;

import java.nio.file.Path;
import java.util.*;

/** Runs the frozen core-source plan through the same persisted source-job launch path as manual requests. */
public final class CoreSourceFanout {
    @FunctionalInterface public interface SourceLauncher { Map<String,Object> launch(RunRequest request) throws Exception; }
    public record Result(boolean ready, BusinessState state, String reason) {}

    private final SqliteLedger ledger;
    private final NativeSourceService sources;
    private final PostCloseSourcePlan plans;

    public CoreSourceFanout(SqliteLedger ledger, NativeSourceService sources, Path archiveRoot) {
        this.ledger = Objects.requireNonNull(ledger);
        this.sources = Objects.requireNonNull(sources);
        this.plans = new PostCloseSourcePlan(archiveRoot);
    }

    public Result execute(RunRequest parent, SourceLauncher launcher) {
        final PostCloseSourcePlan.Manifest manifest;
        try { manifest = plans.read(parent); }
        catch (Exception error) { return blocked(parent, "core-source-plan-invalid:" + error.getMessage()); }

        ledger.stage(parent, "DataReady", BusinessState.RUNNING, null, "core-source-fanout-started:" + manifest.sources().size());
        for (var source : manifest.sources()) {
            String dataset = source.dataset();
            try {
                var request = new SourceCollector.Request(dataset, parent.logicalDate(), source.expectedCodes(), source.universeVersion(), null);
                // Duplicate delivery of one trigger reuses its probe; a deliberate new trigger may
                // retry a failed read while keeping the same durable business-instance identity.
                String triggerIdentity = RunRequest.hash(parent.requestId(), dataset);
                String idempotencyKey = "post-close:" + parent.instanceId() + ":" + triggerIdentity;
                Map<String,Object> probe = sources.collect(idempotencyKey, request);
                String probeState = Objects.toString(probe.get("state"), "");
                if ("RUNNING".equals(probeState))
                    return uncertain(parent, dataset + ":source-probe-still-running");
                if (!"VERIFYING".equals(probeState))
                    return blocked(parent, dataset + ":source-probe-" + Objects.toString(probe.get("state"), "unknown"));
                String json = Objects.toString(probe.get("result_json"), "");
                if (json.isBlank()) return uncertain(parent, dataset + ":source-probe-result-missing");
                SourceCollector.Collected collected = Json.read(json, SourceCollector.Collected.class);
                if (collected.state() != BusinessState.VERIFYING)
                    return blocked(parent, dataset + ":source-probe-" + collected.state());

                String childJob = "source_" + dataset;
                var child = new RunRequest("post-close:" + parent.instanceId() + ":" + triggerIdentity, childJob,
                        parent.logicalDate(), parent.logicalDate(), parent.logicalDate(), source.definitionVersion(),
                        source.revision(), source.supersedes(), source.revisionReason(), collected.fingerprint(),
                        parent.calendarVersion(), parent.zone(), parent.scheduledAt(), parent.triggeredAt(), collected.scopeIdentity());
                launcher.launch(child);
                BusinessState childState = ledger.state(child.instanceId());
                if (!childState.ready()) {
                    if (childState == BusinessState.IN_DOUBT || childState == BusinessState.RUNNING || childState == BusinessState.VERIFYING)
                        return uncertain(parent, dataset + ":source-job-" + childState);
                    return blocked(parent, dataset + ":source-job-" + childState);
                }
            } catch (Exception error) {
                return uncertain(parent, dataset + ":fanout-outcome-unknown:" + error.getClass().getSimpleName());
            }
        }
        return new Result(true, BusinessState.VERIFIED, null);
    }

    private Result blocked(RunRequest request, String reason) {
        ledger.stage(request, "DataReady", BusinessState.BLOCKED, null, reason);
        ledger.state(request.instanceId(), BusinessState.BLOCKED, null, reason);
        return new Result(false, BusinessState.BLOCKED, reason);
    }
    private Result uncertain(RunRequest request, String reason) {
        ledger.stage(request, "DataReady", BusinessState.IN_DOUBT, null, reason);
        ledger.state(request.instanceId(), BusinessState.IN_DOUBT, null, reason);
        return new Result(false, BusinessState.IN_DOUBT, reason);
    }
}
