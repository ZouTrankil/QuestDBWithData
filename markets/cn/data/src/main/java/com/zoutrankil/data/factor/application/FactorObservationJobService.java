package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.Parameter;
import com.zoutrankil.data.domain.SyncJobDefinition.ParameterType;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import com.zoutrankil.data.service.SyncJobRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Finite publication owner; automatic daily scheduling awaits complete producer integration. */
public final class FactorObservationJobService implements SyncJobOwner {
    public static final SyncJobDefinition LEGACY_DEFINITION =
            new SyncJobDefinition(
                    "data.factor_observation",
                    2,
                    "factor_observation",
                    1,
                    "factor_observation_owner",
                    Set.of(Mode.INCREMENTAL, Mode.BACKFILL),
                    Mode.INCREMENTAL,
                    Map.of(
                            "targetId",
                            new Parameter(ParameterType.STRING, true, 128, 1, Set.of()),
                            "factorEvidenceSha256",
                            new Parameter(ParameterType.STRING, true, 64, 1, Set.of())),
                    "local.factor_compute",
                    "factor_observation.date",
                    "questdb.full_key_values",
                    // A 100k-row part requires 400 fully reconciled pages. Local acceptance
                    // measured
                    // about 5.3 seconds/page; retain a finite deadline with room for full
                    // verification.
                    new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofMinutes(5)),
                    Duration.ofHours(1),
                    new Budget(31, 400, 400, 100_000, 1024 * 1024),
                    0,
                    List.of(),
                    Frequency.MANUAL,
                    ZoneId.of("Asia/Shanghai"),
                    true,
                    false);
    public static final SyncJobDefinition DEFINITION = largeBatchDefinition();

    private static SyncJobDefinition largeBatchDefinition() {
        var d = LEGACY_DEFINITION;
        return new SyncJobDefinition(
                d.jobId(),
                3,
                d.datasetId(),
                d.datasetVersion(),
                d.owner(),
                d.supportedModes(),
                d.defaultMode(),
                d.parameters(),
                d.ratePolicyRef(),
                d.slicePolicyRef(),
                d.verificationPolicyRef(),
                d.retry(),
                d.timeout(),
                new Budget(31, 50, 50, 100_000, 4 * 1024 * 1024),
                d.revisionDays(),
                d.dependencies(),
                d.frequency(),
                d.zone(),
                d.enabled(),
                d.dailyEligible());
    }

    public static SyncJobDefinition definition(int version) {
        return switch (version) {
            case 2 -> LEGACY_DEFINITION;
            case 3 -> DEFINITION;
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported factor publication version: " + version);
        };
    }

    public static int pageRows(int version) {
        definition(version);
        return version == 2 ? 250 : 2000;
    }

    private final SyncJobRunner<GlobalFactorObservation, GlobalObservationCoverage.Key> runner;

    public FactorObservationJobService(
            SyncJobRunner<GlobalFactorObservation, GlobalObservationCoverage.Key> runner) {
        this.runner = Objects.requireNonNull(runner);
    }

    public SyncJobDefinition.FrozenRequest plan(
            Mode mode,
            LocalDate from,
            LocalDate through,
            LocalDate logicalDate,
            String targetId,
            String evidenceSha256) {
        if (targetId == null
                || !targetId.matches("static-v2-[0-9a-f]{64}")
                || evidenceSha256 == null
                || !evidenceSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Physical target and evidence identity required");
        if (through.isAfter(logicalDate))
            throw new IllegalArgumentException("Future observation window");
        return DEFINITION.freeze(
                mode,
                Map.of("targetId", targetId, "factorEvidenceSha256", evidenceSha256),
                from,
                through,
                logicalDate);
    }

    public SyncJobRunner.Result execute(
            String runId,
            String priorRunId,
            SyncJobDefinition.FrozenRequest request,
            List<GlobalFactorObservation> rows,
            boolean sourceComplete,
            Path evidence,
            FactorObservationWritePort session,
            BooleanSupplier cancelled)
            throws Exception {
        String physicalTargetId = session.targetIdentity();
        if (!definition(request.definition().version()).equals(request.definition())
                || !physicalTargetId.equals(request.parameters().get("targetId")))
            throw new IllegalArgumentException("Frozen factor job or physical target differs");
        var adapter =
                new FactorObservationSyncAdapter(
                        rows,
                        sourceComplete,
                        evidence,
                        (String) request.parameters().get("factorEvidenceSha256"),
                        session);
        return priorRunId == null
                ? runner.run(runId, null, physicalTargetId, request, adapter, cancelled)
                : runner.resume(runId, priorRunId, physicalTargetId, request, adapter, cancelled);
    }

    @Override
    public String datasetId() {
        return "factor_observation";
    }

    @Override
    public Set<Mode> supportedSyncModes() {
        return DEFINITION.supportedModes();
    }

    @Override
    public List<SyncJobDefinition> syncJobDefinitions() {
        return List.of(DEFINITION);
    }
}
