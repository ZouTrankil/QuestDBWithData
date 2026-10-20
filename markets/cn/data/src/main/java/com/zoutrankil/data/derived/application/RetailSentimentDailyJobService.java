package com.zoutrankil.data.derived.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zoutrankil.data.derived.domain.RetailSentimentDailyLayout;
import com.zoutrankil.data.derived.port.RetailSentimentDailySession;
import com.zoutrankil.data.derived.port.RetailSentimentDailyTarget;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.JobRef;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.Parameter;
import com.zoutrankil.data.domain.SyncJobDefinition.ParameterType;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.derived.RetailSentimentDaily;
import com.zoutrankil.data.domain.derived.RetailSentimentDailyDataset;
import com.zoutrankil.data.domain.derived.RetailSentimentDailySnapshot;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.domain.temporal.MarketId;
import com.zoutrankil.data.domain.temporal.MarketTime;
import com.zoutrankil.data.l2.application.L2DailyFeaturesJobService;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.FrozenRunRequest;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.VerifiedSliceRecovery;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;
import com.zoutrankil.market.cn.time.CnMarketTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Serial native MV materialization with source pinning and the shared ledger/interval lock. */
@Service
public final class RetailSentimentDailyJobService implements SyncJobOwner {
    public static final String JOB_ID = "data.mv_retail_sentiment_daily";

    public record Plan(
            FrozenRequest request, String targetId, RetailSentimentDailySnapshot source) {}

    public record MaterializationResult(
            SyncJobRunner.Result result,
            long sourceRawRows,
            RetailSentimentDailySnapshot source,
            RetailSentimentDailySnapshot target,
            String targetSnapshotError) {}

    public record Status(
            String runId,
            SyncRunState state,
            String targetId,
            String logicalDate,
            int verifiedRows,
            int unresolvedSlices,
            boolean cancellationRequested,
            RetailSentimentDailySnapshot currentTarget,
            String currentTargetError) {}

    private final MarketTime marketTime;
    private final RetailSentimentDailyTarget target;
    private final Path ledgerPath;

    public RetailSentimentDailyJobService(RetailSentimentDailyTarget target, String ledgerPath) {
        this(target, Path.of(ledgerPath));
    }

    @Autowired
    public RetailSentimentDailyJobService(
            RetailSentimentDailyTarget target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            MarketTime marketTime) {
        this(target, Path.of(ledgerPath), marketTime);
    }

    public RetailSentimentDailyJobService(RetailSentimentDailyTarget target, Path ledgerPath) {
        this(target, ledgerPath, CnMarketTime.system());
    }

    public RetailSentimentDailyJobService(
            RetailSentimentDailyTarget target, Path ledgerPath, MarketTime marketTime) {
        this.marketTime = Objects.requireNonNull(marketTime).requireMarket(MarketId.CN);
        this.target = Objects.requireNonNull(target);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
    }

    @Override
    public String datasetId() {
        return RetailSentimentDailyDataset.DEFINITION.datasetId();
    }

    @Override
    public Set<Mode> supportedSyncModes() {
        return currentDefinition().supportedModes();
    }

    @Override
    public List<SyncJobDefinition> syncJobDefinitions() {
        return List.of(currentDefinition());
    }

    public static SyncJobDefinition definition() {
        return definition(1);
    }

    /** v1 原定义不改；v2 另冻结物理作用域，依赖同版 L2 作业。 */
    public static SyncJobDefinition definition(int version) {
        CnTradeDateLayout.fromVersion(version);
        var parameters = new LinkedHashMap<String, Parameter>();
        parameters.put(
                "source_version", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()));
        parameters.put("target_id", new Parameter(ParameterType.STRING, true, 128, 1, Set.of()));
        parameters.put("bootstrap_from", new Parameter(ParameterType.DATE, true, 10, 1, Set.of()));
        parameters.put(
                "calendar_version", new Parameter(ParameterType.STRING, false, 128, 1, Set.of()));
        parameters.put(
                "checkpoint_before", new Parameter(ParameterType.DATE, false, 10, 1, Set.of()));
        parameters.put(
                "native_refresh",
                new Parameter(
                        ParameterType.STRING,
                        false,
                        32,
                        1,
                        Set.of("INCREMENTAL", "FULL_ISOLATED")));
        if (version == 2) {
            parameters.put(
                    "source_table", new Parameter(ParameterType.STRING, true, 127, 1, Set.of()));
            parameters.put(
                    "output_table", new Parameter(ParameterType.STRING, true, 127, 1, Set.of()));
        }
        return new SyncJobDefinition(
                JOB_ID,
                version,
                "mv_retail_sentiment_daily",
                version,
                "retail_sentiment_daily_owner",
                Set.of(Mode.INCREMENTAL, Mode.MATERIALIZE, Mode.RECONCILE),
                Mode.INCREMENTAL,
                parameters,
                "questdb.materialize",
                "retail_sentiment_daily.range31",
                "questdb.full_key_values",
                new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                Duration.ofMinutes(5),
                new Budget(31, 1, 1, 31, 64 * 1024),
                3,
                List.of(
                        new JobRef(
                                L2DailyFeaturesJobService.definition(version).jobId(),
                                L2DailyFeaturesJobService.definition(version).version()),
                        new JobRef("data.exchange_calendar", 1)),
                Frequency.MANUAL,
                ZoneId.of("Asia/Shanghai"),
                true,
                false);
    }

    public Plan plan(
            LocalDate bootstrapFrom, LocalDate requestedTo, LocalDate logicalDate, Mode mode)
            throws Exception {
        var definition = currentDefinition();
        if (bootstrapFrom == null || requestedTo == null || requestedTo.isBefore(bootstrapFrom))
            throw new IllegalArgumentException("Explicit bootstrap anchor and end required");
        Objects.requireNonNull(logicalDate, "Logical date required");
        mode = mode == null ? definition.defaultMode() : mode;
        if (!definition.supportedModes().contains(mode))
            throw new IllegalArgumentException("Unsupported D098 mode");
        var port = port();
        var snapshot = port.snapshot();
        String targetId = port.targetId();
        // A calendar correction can invalidate prefix coverage outside the finite revision overlap.
        // Capture once: checkpoint selection and this frozen request use the same calendar
        // frontier.
        String calendarVersion = mode == Mode.INCREMENTAL ? port.calendarVersion() : null;
        LocalDate checkpoint =
                mode == Mode.INCREMENTAL
                        ? checkpoint(targetId, bootstrapFrom, calendarVersion, snapshot)
                        : null;
        if (mode == Mode.INCREMENTAL && checkpoint != null && checkpoint.isAfter(requestedTo))
            throw new IllegalArgumentException(
                    "Checkpoint is beyond requested end; use bounded RECONCILE");
        LocalDate from = bootstrapFrom;
        if (mode == Mode.INCREMENTAL && checkpoint != null) {
            var overlap = checkpoint.minusDays(definition.revisionDays() - 1L);
            if (overlap.isAfter(from)) from = overlap;
        }
        requireWindow(from, requestedTo);
        var parameters = new LinkedHashMap<String, Object>();
        if (mode == Mode.INCREMENTAL) parameters.put("calendar_version", calendarVersion);
        parameters.put("source_version", snapshot.sourceVersion());
        parameters.put("target_id", targetId);
        parameters.put("bootstrap_from", bootstrapFrom);
        if (checkpoint != null) parameters.put("checkpoint_before", checkpoint);
        bindLayout(parameters, RetailSentimentDailyTarget.layoutOf(this.target));
        return new Plan(
                definition.freeze(mode, parameters, from, requestedTo, logicalDate),
                targetId,
                snapshot);
    }

    public MaterializationResult run(Plan plan) throws Exception {
        return execute(plan, null);
    }

    public MaterializationResult resume(String previousRunId) throws Exception {
        var run = requireRun(SyncRunLedger.openReadOnly(ledgerPath), previousRunId);
        var restored =
                FrozenRunRequest.restore(ledgerPath, previousRunId, definition(run.jobVersion()));
        var port = port(restored.request());
        if (!restored.targetId().equals(port.targetId()))
            throw new IllegalStateException("MV 原物理目标已变化");
        return execute(
                new Plan(restored.request(), restored.targetId(), port.snapshot()), previousRunId);
    }

    private MaterializationResult execute(Plan plan, String previousRunId) throws Exception {
        Objects.requireNonNull(plan);
        var original = definition(plan.request().definition().version());
        if (!original.equals(plan.request().definition())
                || previousRunId == null && !currentDefinition().equals(original))
            throw new IllegalArgumentException("MV 作业定义与当前或原冻结版本不同");
        var port = port(plan.request());
        var adapter = new RetailSentimentDailyMaterializeAdapter(port, plan.source());
        var ledger = new SyncRunLedger(ledgerPath);
        var runner =
                new SyncJobRunner<RetailSentimentDaily, LocalDate>(
                        ledger, new DatasetIntervalLock(ledgerPath));
        String runId = "d098-" + UUID.randomUUID();
        var result =
                previousRunId == null
                        ? runner.run(
                                runId, null, plan.targetId(), plan.request(), adapter, () -> false)
                        : runner.resume(
                                runId,
                                previousRunId,
                                plan.targetId(),
                                plan.request(),
                                adapter,
                                () -> false);
        var observed = safeSnapshot(port);
        RetailSentimentDailySnapshot verified = adapter.lastVerificationSnapshot();
        String snapshotError = observed.error;
        if (verified != null && observed.snapshot != null && !verified.equals(observed.snapshot))
            snapshotError = "TargetChangedAfterVerification";
        return new MaterializationResult(
                result,
                adapter.sourceRawRows(),
                plan.source(),
                verified == null ? observed.snapshot : verified,
                snapshotError);
    }

    public RetailSentimentDailySnapshot installIsolated() throws Exception {
        var port = port();
        port.createIsolatedTarget();
        return port.snapshot();
    }

    /** The isolated FULL operation has the same durable intent, exclusion and verification as every materialization. */
    public MaterializationResult repairIsolated() throws Exception {
        var definition = currentDefinition();
        var port = port();
        var scope = port.fullSourceScope();
        var source = port.snapshot();
        String target = port.targetId();
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("source_version", source.sourceVersion());
        parameters.put("target_id", target);
        parameters.put("bootstrap_from", scope.from());
        parameters.put("native_refresh", "FULL_ISOLATED");
        bindLayout(parameters, RetailSentimentDailyTarget.layoutOf(this.target));
        var request =
                definition.freeze(
                        Mode.MATERIALIZE, parameters, scope.from(), scope.to(), marketTime.today());
        return run(new Plan(request, target, source));
    }

    public Status status(String runId) throws Exception {
        var ledger = new SyncRunLedger(ledgerPath);
        var run = requireRun(ledger, runId);
        int verified = 0, unresolved = 0;
        for (var entry : children(ledger, runId)) {
            if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
            if (entry.state() == SyncRunState.VERIFIED
                    || entry.state() == SyncRunState.VERIFIED_EMPTY)
                verified +=
                        JobDefinitionJson.mapper()
                                .readTree(entry.payloadJson())
                                .path("verification")
                                .path("matchedRows")
                                .asInt(0);
            else unresolved++;
        }
        var observed = safeSnapshot(run);
        return new Status(
                runId,
                ledger.get(runId).state(),
                run.targetId(),
                run.logicalDate(),
                verified,
                unresolved,
                ledger.cancellationRequested(runId),
                observed.snapshot,
                observed.error);
    }

    public boolean cancel(String runId) throws Exception {
        var ledger = new SyncRunLedger(ledgerPath);
        requireRun(ledger, runId);
        return ledger.requestCancellation(runId);
    }

    /** Reconciliation observes current values only; it never resubmits a refresh. */
    public Status reconcile(String runId, boolean writerStopped) throws Exception {
        if (!writerStopped)
            throw new IllegalArgumentException("Explicit stopped-writer proof required");
        var ledger = new SyncRunLedger(ledgerPath);
        var run = requireRun(ledger, runId);
        var definition = definition(run.jobVersion());
        var priorState = ledger.get(runId).state();
        if (!Set.of(SyncRunState.IN_DOUBT, SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                .contains(priorState))
            throw new IllegalStateException("Run has no uncertain outcome to reconcile");
        var saved = JobDefinitionJson.mapper().readTree(run.frozenJson());
        var parameters = new LinkedHashMap<String, Object>();
        for (var spec : definition.parameters().entrySet()) {
            var value = saved.path("parameters").get(spec.getKey());
            if (value != null && !value.isNull())
                parameters.put(
                        spec.getKey(),
                        spec.getValue().type() == ParameterType.DATE
                                ? LocalDate.parse(value.asText())
                                : value.asText());
        }
        var request =
                definition.freeze(
                        Mode.valueOf(saved.path("mode").asText()),
                        parameters,
                        LocalDate.parse(saved.path("from").asText()),
                        LocalDate.parse(saved.path("to").asText()),
                        LocalDate.parse(saved.path("logicalDate").asText()));
        if (!SyncRequestIdentity.fingerprint(request, run.targetId())
                .equals(SyncRequestIdentity.fingerprint(run.frozenJson(), run.targetId())))
            throw new IllegalStateException("Frozen definition changed before reconciliation");
        var port = port(request);
        var adapter = new RetailSentimentDailyMaterializeAdapter(port, port.snapshot());
        var completion = adapter.revalidateOnly(request, () -> false);
        var details = JobDefinitionJson.mapper().readTree(completion.evidence());
        int verifiedCount = completion.rows();
        String fingerprint = details.path("sourceFingerprint").asText("");
        if (!completion.complete()
                || fingerprint.isBlank()
                || adapter.lastVerificationSnapshot() == null)
            throw new IllegalStateException("Incomplete reconciliation evidence");
        var locks = new DatasetIntervalLock(ledgerPath);
        var lease = locks.findOwned(runId, adapter.conflictScope(request));
        // Older versions persisted the exact window. Read-only reconciliation still requires
        // the same real value and stable version proof before releasing that retained lease.
        if (lease == null)
            lease =
                    locks.findOwned(
                            runId,
                            new DatasetIntervalLock.Scope(
                                    datasetId(), request.from(), request.to()));
        if (lease == null || !lease.inDoubt())
            throw new IllegalStateException("Uncertain run lost its interval exclusion");
        var verifiedSnapshot = adapter.lastVerificationSnapshot();
        if (!verifiedSnapshot.equals(port.snapshot()))
            throw new IllegalStateException("D098 changed after reconciliation readback");
        if (request.mode() == Mode.INCREMENTAL
                && !port.calendarVersion().equals(request.parameters().get("calendar_version")))
            throw new IllegalStateException("D098 calendar changed after reconciliation");
        var proof = new LinkedHashMap<String, Object>();
        proof.put(
                "verification",
                Map.of(
                        "passed",
                        true,
                        "expectedRows",
                        verifiedCount,
                        "actualRows",
                        verifiedCount,
                        "matchedRows",
                        verifiedCount,
                        "mismatchedRows",
                        0,
                        "duplicateKeys",
                        0,
                        "missingKeys",
                        0,
                        "sourceFingerprint",
                        fingerprint,
                        "readbackEvidence",
                        "d098-reconcile:" + verifiedSnapshot.stableVersion(),
                        "writerStopped",
                        true));
        if (verifiedCount == 0) {
            proof.put("sourceComplete", true);
            proof.put("returnedRows", 0);
            proof.put("submittedRows", 0);
            proof.put("responseEvidence", completion.evidence());
        }
        String payload = JobDefinitionJson.mapper().writeValueAsString(proof);
        var state = verifiedCount == 0 ? SyncRunState.VERIFIED_EMPTY : SyncRunState.VERIFIED;
        // Verify every uncertain child before completing its owner and releasing exclusion.
        for (var entry : children(ledger, runId))
            if (entry.kind() == SyncRunLedger.Kind.SLICE && entry.state() == SyncRunState.IN_DOUBT)
                ledger.transition(entry.id(), entry.revision(), state, payload);
        for (var entry : children(ledger, runId))
            if (entry.kind() == SyncRunLedger.Kind.ATTEMPT
                    && entry.state() == SyncRunState.IN_DOUBT)
                ledger.transition(entry.id(), entry.revision(), state, payload);
        var owner = ledger.get(runId);
        if (owner.state() == SyncRunState.IN_DOUBT)
            ledger.transition(runId, owner.revision(), state, payload);
        else if (owner.state() != state)
            throw new IllegalStateException("Reconciled outcome differs from completed run");
        locks.releaseAfterReconciliation(lease, true, true);
        return status(runId);
    }

    private SyncRunLedger.Run requireRun(SyncRunLedger ledger, String runId) throws Exception {
        var run = ledger.getRun(runId);
        if (!JOB_ID.equals(run.jobId()) || (run.jobVersion() < 1 || run.jobVersion() > 2))
            throw new IllegalArgumentException("Run does not belong to D098");
        return run;
    }

    private List<SyncRunLedger.Entry> children(SyncRunLedger ledger, String runId)
            throws Exception {
        var entries = new ArrayList<SyncRunLedger.Entry>();
        String cursor = null;
        while (true) {
            var page = ledger.entries(runId, cursor, 1000);
            if (page.isEmpty()) break;
            entries.addAll(page);
            if (entries.size() > 100)
                throw new IllegalStateException("D098 ledger child budget exceeded");
            cursor = page.getLast().id();
        }
        return entries;
    }

    private record Prefix(SyncRunLedger.Run run, FrozenRequest request) {}

    /** 旧窗口只决定复核范围；当前完整性来自新的只读证明，不重标旧来源版本。 */
    private LocalDate checkpoint(
            String targetId,
            LocalDate bootstrap,
            String calendarVersion,
            RetailSentimentDailySnapshot planned)
            throws Exception {
        var ledger = new SyncRunLedger(ledgerPath);
        var intervals = new ArrayList<Prefix>();
        String cursor = null;
        int pages = 0;
        while (true) {
            var history = ledger.history(JOB_ID, cursor, 1000);
            if (history.isEmpty()) break;
            for (var item : history) {
                if (!targetId.equals(item.targetId())
                        || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                                .contains(item.state())) continue;
                var run = ledger.getRun(item.id());
                if (run.jobVersion() != currentDefinition().version()) continue;
                var request = parseFrozen(run);
                if (!bootstrap.equals(request.parameters().get("bootstrap_from"))
                        || request.mode() != Mode.INCREMENTAL
                        || !calendarVersion.equals(request.parameters().get("calendar_version")))
                    continue;
                if (!frozenLayout(request).equals(RetailSentimentDailyTarget.layoutOf(target)))
                    continue;
                var payload =
                        JobDefinitionJson.mapper().readTree(ledger.get(run.id()).payloadJson());
                if (item.state() == SyncRunState.VERIFIED) {
                    var proof = payload.path("verification");
                    if (!proof.path("passed").asBoolean()
                            || proof.path("expectedRows").asLong(-1) < 1
                            || proof.path("sourceFingerprint").asText("").isBlank()
                            || proof.path("readbackEvidence").asText("").isBlank())
                        throw new IllegalStateException("MV 历史成功记录缺少完整证明");
                } else if (!payload.path("sourceComplete").asBoolean()
                        || payload.path("returnedRows").asLong(-1) != 0
                        || payload.path("responseEvidence").asText("").isBlank())
                    throw new IllegalStateException("MV 历史空窗缺少完整来源证明");
                // 不跳过已有未决子项；旧父项本身不代替当前值与来源验收。
                VerifiedSliceRecovery.load(ledger, run.id(), request, run.targetId());
                intervals.add(new Prefix(run, request));
            }
            cursor = history.getLast().id();
            if (++pages > 100)
                throw new IllegalStateException("D098 checkpoint scan budget exceeded");
        }
        intervals.sort(Comparator.comparing(value -> value.request().from()));
        LocalDate next = bootstrap;
        for (var interval : intervals)
            if (!interval.request().from().isAfter(next)
                    && !interval.request().to().isBefore(next)) {
                revalidatePrefix(interval, targetId, calendarVersion, planned);
                next = interval.request().to().plusDays(1);
            }
        return next.equals(bootstrap) ? null : next.minusDays(1);
    }

    private void revalidatePrefix(
            Prefix prior,
            String identity,
            String calendarVersion,
            RetailSentimentDailySnapshot planned)
            throws Exception {
        var original = prior.request();
        var port = port(original);
        var before = port.snapshot();
        if (!identity.equals(port.targetId())
                || !planned.sourceUnchanged(before)
                || !before.valid()
                || !before.caughtUp()
                || planned.mvId() != before.mvId()
                || !planned.mvDirectory().equals(before.mvDirectory())
                || !planned.definitionSha().equals(before.definitionSha()))
            throw new IllegalStateException("MV 当前前缀来源或物理对象未稳定");
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("source_version", before.sourceVersion());
        parameters.put("target_id", identity);
        parameters.put("bootstrap_from", original.parameters().get("bootstrap_from"));
        parameters.put("calendar_version", calendarVersion);
        bindLayout(parameters, frozenLayout(original));
        // 这是当前基表的独立复核请求，不写回原运行，也不把新版本当成原冻结输入。
        var request =
                definition(original.definition().version())
                        .freeze(
                                Mode.INCREMENTAL,
                                parameters,
                                original.from(),
                                original.to(),
                                original.logicalDate());
        var adapter = new RetailSentimentDailyMaterializeAdapter(port, before);
        var completion =
                adapter.revalidateOnly(request, () -> Thread.currentThread().isInterrupted());
        var after = port.snapshot();
        if (!completion.complete()
                || completion.evidence() == null
                || completion.evidence().isBlank()
                || adapter.lastVerificationSnapshot() == null
                || !adapter.lastVerificationSnapshot().equals(after)
                || !planned.sourceUnchanged(after)
                || !calendarVersion.equals(port.calendarVersion()))
            throw new IllegalStateException("MV 当前前缀完整复核失败");
        var evidence =
                ledgerPath
                        .getParent()
                        .resolve("sync-evidence")
                        .resolve("retail-prefix-" + UUID.randomUUID() + ".json");
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", "java.retail_sentiment_prefix_revalidation.v2");
        report.put("originalRunId", prior.run().id());
        report.put(
                "originalRequestFingerprint",
                SyncRequestIdentity.fingerprint(prior.run().frozenJson(), identity));
        report.put(
                "currentRequest",
                JobDefinitionJson.mapper().readTree(SyncRequestIdentity.snapshotJson(request)));
        report.put("currentSnapshot", after);
        report.put("verifiedAt", Instant.now().toString());
        report.put("sourceEvidence", completion.evidence());
        report.put("passed", true);
        Files.createDirectories(evidence.getParent());
        FileEvidenceStore.writeNewUtf8(
                evidence, JobDefinitionJson.mapper().writeValueAsString(report));
    }

    private static void requireWindow(LocalDate from, LocalDate to) {
        if (from == null
                || to == null
                || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) >= 31)
            throw new IllegalArgumentException(
                    "Explicit nonempty D098 window of at most 31 days required");
    }

    private record ObservedTarget(RetailSentimentDailySnapshot snapshot, String error) {}

    private ObservedTarget safeSnapshot() {
        try {
            return safeSnapshot(port());
        } catch (RuntimeException failure) {
            return new ObservedTarget(null, failure.getClass().getSimpleName());
        }
    }

    private static ObservedTarget safeSnapshot(RetailSentimentDailySession port) {
        try {
            return new ObservedTarget(port.snapshot(), null);
        } catch (RuntimeException failure) {
            return new ObservedTarget(null, failure.getClass().getSimpleName());
        }
    }

    private SyncJobDefinition currentDefinition() {
        return definition(RetailSentimentDailyTarget.layoutOf(target).version());
    }

    private static void bindLayout(
            Map<String, Object> parameters, RetailSentimentDailyLayout layout) {
        if (layout.version() == 2) {
            parameters.put("source_table", layout.sourceTable());
            parameters.put("output_table", layout.outputTable());
        }
    }

    public static RetailSentimentDailyLayout frozenLayout(FrozenRequest request) {
        if (!request.definition().equals(definition(request.definition().version())))
            throw new IllegalArgumentException("MV 原定义不是 canonical 版本");
        if (request.definition().version() == 1) return RetailSentimentDailyLayout.LEGACY;
        return new RetailSentimentDailyLayout(
                CnTradeDateLayout.TRADE_DATE_V2,
                (String) request.parameters().get("source_table"),
                (String) request.parameters().get("output_table"));
    }

    private RetailSentimentDailySession port(FrozenRequest request) {
        var layout = frozenLayout(request);
        var session = RetailSentimentDailyTarget.forFrozenLayout(target, layout).newSession();
        if (!layout.equals(RetailSentimentDailySession.layoutOf(session)))
            throw new IllegalStateException("MV 会话与原冻结作用域不同");
        return session;
    }

    private ObservedTarget safeSnapshot(SyncRunLedger.Run run) {
        try {
            return safeSnapshot(port(parseFrozen(run)));
        } catch (Exception failure) {
            return new ObservedTarget(null, failure.getClass().getSimpleName());
        }
    }

    private FrozenRequest parseFrozen(SyncRunLedger.Run run) throws Exception {
        var expected = definition(run.jobVersion());
        var json = JobDefinitionJson.mapper();
        var saved = json.readTree(run.frozenJson());
        var actual = json.treeToValue(saved.path("definition"), SyncJobDefinition.class);
        if (!expected.equals(actual)) throw new IllegalStateException("MV 原定义发生变化");
        var parameters =
                json.convertValue(
                        saved.path("parameters"),
                        new TypeReference<LinkedHashMap<String, Object>>() {});
        for (var spec : expected.parameters().entrySet())
            if (parameters.containsKey(spec.getKey())
                    && spec.getValue().type() == ParameterType.DATE)
                parameters.put(
                        spec.getKey(),
                        LocalDate.parse(saved.path("parameters").path(spec.getKey()).asText()));
        var request =
                expected.freeze(
                        Mode.valueOf(saved.path("mode").asText()),
                        parameters,
                        LocalDate.parse(saved.path("from").asText()),
                        LocalDate.parse(saved.path("to").asText()),
                        LocalDate.parse(saved.path("logicalDate").asText()));
        if (!SyncRequestIdentity.fingerprint(request, run.targetId())
                .equals(SyncRequestIdentity.fingerprint(run.frozenJson(), run.targetId())))
            throw new IllegalStateException("MV 原冻结身份不匹配");
        return request;
    }

    private RetailSentimentDailySession port() {
        var session = target.newSession();
        if (!RetailSentimentDailyTarget.layoutOf(target)
                .equals(RetailSentimentDailySession.layoutOf(session)))
            throw new IllegalStateException("MV 当前会话布局与目标不同");
        return session;
    }
}
