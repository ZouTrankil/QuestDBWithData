package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.l2.application.L2DailyFeaturesJobService;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.port.RetailSentimentDailyV1Session;
import com.zoutrankil.data.derived.port.RetailSentimentDailyV1Target;
import com.zoutrankil.data.domain.RetailSentimentDailyV1Snapshot;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Serial native MV materialization with source pinning and the shared ledger/interval lock. */
@Service
public final class RetailSentimentDailyV1JobService implements SyncJobOwner {
    public static final String JOB_ID = "data.mv_retail_sentiment_daily_v1";
    public record Plan(FrozenRequest request, String targetId, RetailSentimentDailyV1Snapshot source) {}
    public record MaterializationResult(SyncJobRunner.Result result, long sourceRawRows,
                                        RetailSentimentDailyV1Snapshot source, RetailSentimentDailyV1Snapshot target, String targetSnapshotError) {}
    public record Status(String runId, SyncRunState state, String targetId, String logicalDate,
                         int verifiedRows, int unresolvedSlices, boolean cancellationRequested,
                         RetailSentimentDailyV1Snapshot currentTarget, String currentTargetError) {}
    private final RetailSentimentDailyV1Target target;
    private final Path ledgerPath;

    @Autowired
    public RetailSentimentDailyV1JobService(RetailSentimentDailyV1Target target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(target, Path.of(ledgerPath));
    }
    public RetailSentimentDailyV1JobService(RetailSentimentDailyV1Target target, Path ledgerPath) {
        this.target = Objects.requireNonNull(target);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
    }
    @Override public String datasetId() { return RetailSentimentDailyV1Dataset.DEFINITION.datasetId(); }
    @Override public Set<Mode> supportedSyncModes() { return definition().supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(definition()); }
    public static SyncJobDefinition definition() {
        var parameters = new LinkedHashMap<String, Parameter>();
        parameters.put("source_version", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()));
        parameters.put("target_id", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()));
        parameters.put("bootstrap_from", new Parameter(ParameterType.DATE, true, 10, 1, Set.of()));
        parameters.put("calendar_version", new Parameter(ParameterType.STRING, false, 128, 1, Set.of()));
        parameters.put("checkpoint_before", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()));
        parameters.put("native_refresh", new Parameter(ParameterType.STRING, false, 32, 1,
                Set.of("INCREMENTAL", "FULL_ISOLATED")));
        return new SyncJobDefinition(JOB_ID, 1, "mv_retail_sentiment_daily_v1", 1,
                "retail_sentiment_daily_owner", Set.of(Mode.INCREMENTAL, Mode.MATERIALIZE, Mode.RECONCILE),
                Mode.INCREMENTAL, parameters, "questdb.materialize", "retail_sentiment_daily.range31",
                "questdb.full_key_values", new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                Duration.ofMinutes(5), new Budget(31, 1, 1, 31, 64 * 1024), 3,
                List.of(new JobRef(L2DailyFeaturesJobService.definition().jobId(), L2DailyFeaturesJobService.definition().version()),
                        new JobRef("data.exchange_calendar", 1)), Frequency.MANUAL,
                ZoneId.of("Asia/Shanghai"), true, false);
    }
    public Plan plan(LocalDate bootstrapFrom, LocalDate requestedTo, LocalDate logicalDate, Mode mode) throws Exception {
        if (bootstrapFrom == null || requestedTo == null || requestedTo.isBefore(bootstrapFrom))
            throw new IllegalArgumentException("Explicit bootstrap anchor and end required");
        Objects.requireNonNull(logicalDate, "Logical date required");
        mode = mode == null ? definition().defaultMode() : mode;
        if (!definition().supportedModes().contains(mode)) throw new IllegalArgumentException("Unsupported D098 mode");
        var port = port();
        var snapshot = port.snapshot();
        String targetId = port.targetId();
        // A calendar correction can invalidate prefix coverage outside the finite revision overlap.
        // Capture once: checkpoint selection and this frozen request use the same calendar frontier.
        String calendarVersion = mode == Mode.INCREMENTAL ? port.calendarVersion() : null;
        LocalDate checkpoint = mode == Mode.INCREMENTAL
                ? checkpoint(targetId, bootstrapFrom, calendarVersion) : null;
        if (mode == Mode.INCREMENTAL && checkpoint != null && checkpoint.isAfter(requestedTo))
            throw new IllegalArgumentException("Checkpoint is beyond requested end; use bounded RECONCILE");
        LocalDate from = bootstrapFrom;
        if (mode == Mode.INCREMENTAL && checkpoint != null) {
            var overlap = checkpoint.minusDays(definition().revisionDays() - 1L);
            if (overlap.isAfter(from)) from = overlap;
        }
        requireWindow(from, requestedTo);
        var parameters = new LinkedHashMap<String,Object>();
        if (mode == Mode.INCREMENTAL) parameters.put("calendar_version", calendarVersion);
        parameters.put("source_version", snapshot.sourceVersion());
        parameters.put("target_id", targetId);
        parameters.put("bootstrap_from", bootstrapFrom);
        if (checkpoint != null) parameters.put("checkpoint_before", checkpoint);
        return new Plan(definition().freeze(mode, parameters, from, requestedTo, logicalDate), targetId, snapshot);
    }
    public MaterializationResult run(Plan plan) throws Exception { return execute(plan, null); }
    public MaterializationResult resume(String previousRunId) throws Exception {
        var restored = FrozenRunRequest.restore(ledgerPath, previousRunId, definition());
        var port = port();
        return execute(new Plan(restored.request(), restored.targetId(), port.snapshot()), previousRunId);
    }
    private MaterializationResult execute(Plan plan, String previousRunId) throws Exception {
        Objects.requireNonNull(plan);
        if (!definition().equals(plan.request().definition())) throw new IllegalArgumentException("D098 definition changed");
        var port = port();
        var adapter = new RetailSentimentDailyV1MaterializeAdapter(port, plan.source());
        var ledger = new SyncRunLedger(ledgerPath);
        var runner = new SyncJobRunner<RetailSentimentDailyV1,LocalDate>(ledger, new DatasetIntervalLock(ledgerPath));
        String runId = "d098-" + UUID.randomUUID();
        var result = previousRunId == null
                ? runner.run(runId, null, plan.targetId(), plan.request(), adapter, () -> false)
                : runner.resume(runId, previousRunId, plan.targetId(), plan.request(), adapter, () -> false);
        var observed = safeSnapshot(port);
        RetailSentimentDailyV1Snapshot verified = adapter.lastVerificationSnapshot();
        String snapshotError = observed.error;
        if (verified != null && observed.snapshot != null && !verified.equals(observed.snapshot))
            snapshotError = "TargetChangedAfterVerification";
        return new MaterializationResult(result, adapter.sourceRawRows(), plan.source(),
                verified == null ? observed.snapshot : verified, snapshotError);
    }
    public RetailSentimentDailyV1Snapshot installIsolated() throws Exception { var port = port(); port.createIsolatedTarget(); return port.snapshot(); }
    /** The isolated FULL operation has the same durable intent, exclusion and verification as every materialization. */
    public MaterializationResult repairIsolated() throws Exception {
        var port = port();
        var scope = port.fullSourceScope();
        var source = port.snapshot();
        String target = port.targetId();
        var parameters = new LinkedHashMap<String,Object>();
        parameters.put("source_version", source.sourceVersion());
        parameters.put("target_id", target);
        parameters.put("bootstrap_from", scope.from());
        parameters.put("native_refresh", "FULL_ISOLATED");
        var request = definition().freeze(Mode.MATERIALIZE, parameters, scope.from(), scope.to(),
                LocalDate.now(ZoneId.of("Asia/Shanghai")));
        return run(new Plan(request, target, source));
    }
    public Status status(String runId) throws Exception {
        var ledger = new SyncRunLedger(ledgerPath);
        var run = requireRun(ledger, runId);
        int verified = 0, unresolved = 0;
        for (var entry : children(ledger, runId)) {
            if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
            if (entry.state() == SyncRunState.VERIFIED || entry.state() == SyncRunState.VERIFIED_EMPTY)
                verified += JobDefinitionJson.mapper().readTree(entry.payloadJson()).path("verification").path("matchedRows").asInt(0);
            else unresolved++;
        }
        var observed = safeSnapshot();
        return new Status(runId, ledger.get(runId).state(), run.targetId(), run.logicalDate(), verified,
                unresolved, ledger.cancellationRequested(runId), observed.snapshot, observed.error);
    }
    public boolean cancel(String runId) throws Exception {
        var ledger = new SyncRunLedger(ledgerPath);
        requireRun(ledger, runId);
        return ledger.requestCancellation(runId);
    }
    /** Reconciliation observes current values only; it never resubmits a refresh. */
    public Status reconcile(String runId, boolean writerStopped) throws Exception {
        if (!writerStopped) throw new IllegalArgumentException("Explicit stopped-writer proof required");
        var ledger = new SyncRunLedger(ledgerPath);
        var run = requireRun(ledger, runId);
        var priorState = ledger.get(runId).state();
        if (!Set.of(SyncRunState.IN_DOUBT,SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(priorState))
            throw new IllegalStateException("Run has no uncertain outcome to reconcile");
        var saved = JobDefinitionJson.mapper().readTree(run.frozenJson());
        var parameters = new LinkedHashMap<String,Object>();
        for (var spec : definition().parameters().entrySet()) {
            var value = saved.path("parameters").get(spec.getKey());
            if (value != null && !value.isNull()) parameters.put(spec.getKey(), spec.getValue().type() == ParameterType.DATE
                    ? LocalDate.parse(value.asText()) : value.asText());
        }
        var request = definition().freeze(Mode.valueOf(saved.path("mode").asText()), parameters,
                LocalDate.parse(saved.path("from").asText()), LocalDate.parse(saved.path("to").asText()),
                LocalDate.parse(saved.path("logicalDate").asText()));
        if (!SyncRequestIdentity.fingerprint(request, run.targetId()).equals(SyncRequestIdentity.fingerprint(run.frozenJson(), run.targetId())))
            throw new IllegalStateException("Frozen definition changed before reconciliation");
        var port = port();
        var adapter = new RetailSentimentDailyV1MaterializeAdapter(port, port.snapshot());
        var completion = adapter.revalidateOnly(request, () -> false);
        var details = JobDefinitionJson.mapper().readTree(completion.evidence());
        int verifiedCount = completion.rows();
        String fingerprint = details.path("sourceFingerprint").asText("");
        if (!completion.complete() || fingerprint.isBlank() || adapter.lastVerificationSnapshot() == null)
            throw new IllegalStateException("Incomplete reconciliation evidence");
        var locks = new DatasetIntervalLock(ledgerPath);
        var lease = locks.findOwned(runId, adapter.conflictScope(request));
        // Older versions persisted the exact window. Read-only reconciliation still requires
        // the same real value and stable version proof before releasing that retained lease.
        if (lease == null) lease = locks.findOwned(runId,
                new DatasetIntervalLock.Scope(datasetId(), request.from(), request.to()));
        if (lease == null || !lease.inDoubt()) throw new IllegalStateException("Uncertain run lost its interval exclusion");
        var verifiedSnapshot = adapter.lastVerificationSnapshot();
        if (!verifiedSnapshot.equals(port.snapshot())) throw new IllegalStateException("D098 changed after reconciliation readback");
        if (request.mode() == Mode.INCREMENTAL && !port.calendarVersion().equals(request.parameters().get("calendar_version")))
            throw new IllegalStateException("D098 calendar changed after reconciliation");
        var proof = new LinkedHashMap<String,Object>();
        proof.put("verification", Map.of("passed",true,"expectedRows",verifiedCount,"actualRows",verifiedCount,
                "matchedRows",verifiedCount,"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,
                "sourceFingerprint",fingerprint,"readbackEvidence","d098-reconcile:" + verifiedSnapshot.stableVersion(),"writerStopped",true));
        if (verifiedCount == 0) { proof.put("sourceComplete",true); proof.put("returnedRows",0); proof.put("submittedRows",0); proof.put("responseEvidence",completion.evidence()); }
        String payload = JobDefinitionJson.mapper().writeValueAsString(proof);
        var state = verifiedCount == 0 ? SyncRunState.VERIFIED_EMPTY : SyncRunState.VERIFIED;
        // Verify every uncertain child before completing its owner and releasing exclusion.
        for (var entry : children(ledger, runId)) if (entry.kind() == SyncRunLedger.Kind.SLICE && entry.state() == SyncRunState.IN_DOUBT)
            ledger.transition(entry.id(), entry.revision(), state, payload);
        for (var entry : children(ledger, runId)) if (entry.kind() == SyncRunLedger.Kind.ATTEMPT && entry.state() == SyncRunState.IN_DOUBT)
            ledger.transition(entry.id(), entry.revision(), state, payload);
        var owner = ledger.get(runId);
        if (owner.state() == SyncRunState.IN_DOUBT) ledger.transition(runId, owner.revision(), state, payload);
        else if (owner.state() != state) throw new IllegalStateException("Reconciled outcome differs from completed run");
        locks.releaseAfterReconciliation(lease, true, true);
        return status(runId);
    }
    private SyncRunLedger.Run requireRun(SyncRunLedger ledger, String runId) throws Exception {
        var run = ledger.getRun(runId);
        if (!JOB_ID.equals(run.jobId()) || run.jobVersion() != definition().version()) throw new IllegalArgumentException("Run does not belong to D098");
        return run;
    }
    private List<SyncRunLedger.Entry> children(SyncRunLedger ledger, String runId) throws Exception {
        var entries = new ArrayList<SyncRunLedger.Entry>(); String cursor = null;
        while (true) {
            var page = ledger.entries(runId, cursor, 1000); if (page.isEmpty()) break;
            entries.addAll(page); if (entries.size() > 100) throw new IllegalStateException("D098 ledger child budget exceeded");
            cursor = page.getLast().id();
        }
        return entries;
    }
    /** Only contiguous verified windows from this bootstrap advance an incremental checkpoint. */
    private LocalDate checkpoint(String targetId, LocalDate bootstrap, String calendarVersion) throws Exception {
        var ledger = new SyncRunLedger(ledgerPath);
        var intervals = new ArrayList<LocalDate[]>(); String cursor = null; int pages = 0;
        while (true) {
            var history = ledger.history(JOB_ID, cursor, 1000); if (history.isEmpty()) break;
            for (var item : history) {
                if (!targetId.equals(item.targetId()) || !Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(item.state())) continue;
                JsonNode saved = JobDefinitionJson.mapper().readTree(ledger.getRun(item.id()).frozenJson());
                if (!bootstrap.toString().equals(saved.path("parameters").path("bootstrap_from").asText())
                        || !Mode.INCREMENTAL.name().equals(saved.path("mode").asText())
                        || !calendarVersion.equals(saved.path("parameters").path("calendar_version").asText())) continue;
                intervals.add(new LocalDate[]{LocalDate.parse(saved.path("from").asText()),LocalDate.parse(saved.path("to").asText())});
            }
            cursor = history.getLast().id(); if (++pages > 100) throw new IllegalStateException("D098 checkpoint scan budget exceeded");
        }
        intervals.sort(Comparator.comparing(row -> row[0])); LocalDate next = bootstrap;
        for (var interval : intervals) if (!interval[0].isAfter(next) && !interval[1].isBefore(next)) next = interval[1].plusDays(1);
        return next.equals(bootstrap) ? null : next.minusDays(1);
    }
    private static void requireWindow(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from,to) >= 31)
            throw new IllegalArgumentException("Explicit nonempty D098 window of at most 31 days required");
    }
    private record ObservedTarget(RetailSentimentDailyV1Snapshot snapshot, String error) {}
    private ObservedTarget safeSnapshot() {
        try { return safeSnapshot(port()); }
        catch (RuntimeException failure) { return new ObservedTarget(null, failure.getClass().getSimpleName()); }
    }
    private static ObservedTarget safeSnapshot(RetailSentimentDailyV1Session port) {
        try { return new ObservedTarget(port.snapshot(), null); }
        catch (RuntimeException failure) { return new ObservedTarget(null, failure.getClass().getSimpleName()); }
    }
    private RetailSentimentDailyV1Session port() { return target.newSession(); }
}
