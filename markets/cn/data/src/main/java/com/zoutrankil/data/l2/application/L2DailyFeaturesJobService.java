package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
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
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesDataset;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesKey;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.l2.domain.L2DailyFeaturesRows;
import com.zoutrankil.data.l2.port.L2DailyFeaturesTarget;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.CompletedSourceRevalidation;
import com.zoutrankil.data.service.DailyTradingSessions;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.FrozenRunRequest;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.VerifiedSliceRecovery;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** D086 owner for bounded manifest-certified daily feature Parquet synchronization. */
@Service
public final class L2DailyFeaturesJobService implements SyncJobOwner {
    public record Plan(
            FrozenRequest request,
            String targetId,
            LocalDate bootstrapFrom,
            LocalDate verifiedThrough,
            int targetDatesChecked,
            L2DailyFeaturesParquetSource.Inspection source) {
        public Plan {
            Objects.requireNonNull(request);
            Objects.requireNonNull(targetId);
            Objects.requireNonNull(bootstrapFrom);
            Objects.requireNonNull(source);
            if (targetDatesChecked < 0)
                throw new IllegalArgumentException("Negative D086 target date count");
        }
    }

    public record Status(
            String runId,
            SyncRunState state,
            String targetId,
            String logicalDate,
            int sourceRows,
            int verifiedRows,
            int verifiedSlices,
            int unresolvedSlices,
            List<String> errorCodes) {
        public Status {
            errorCodes = List.copyOf(errorCodes);
        }
    }

    private static final ObjectMapper JSON = JobDefinitionJson.mapper();
    private static final String JOB_ID = "data.l2_daily_features";
    private static final DateTimeFormatter RUN_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'");

    private final ExchangeCalendarReadPort calendars;
    private final L2DailyFeaturesTarget target;
    private final Path ledgerPath;
    private final L2DailyFeaturesParquetSource source;

    @Autowired
    public L2DailyFeaturesJobService(
            ExchangeCalendarReadPort calendars,
            L2DailyFeaturesTarget target,
            L2DailyFeaturesParquetSource source,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this.calendars = Objects.requireNonNull(calendars);
        this.target = Objects.requireNonNull(target);
        this.ledgerPath = Path.of(ledgerPath).toAbsolutePath().normalize();
        this.source = Objects.requireNonNull(source);
    }

    public L2DailyFeaturesJobService(
            ExchangeCalendarReadPort calendars,
            L2DailyFeaturesTarget target,
            L2DailyFeaturesParquetSource source,
            Path ledgerPath) {
        this(calendars, target, source, ledgerPath.toString());
    }

    @Override
    public String datasetId() {
        return L2DailyFeaturesDataset.DEFINITION.datasetId();
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

    /** 原定义字节及预算保持不动；新版定义只供显式来源装配，当前控制入口仍为 v1。 */
    public static SyncJobDefinition definition(int version) {
        CnTradeDateLayout.fromVersion(version);
        var parameters = new LinkedHashMap<String, Parameter>();
        parameters.put(
                "source_root_id", new Parameter(ParameterType.STRING, true, 64, 1, Set.of()));
        parameters.put(
                "symbols", new Parameter(ParameterType.STRING_LIST, false, 16, 1000, Set.of()));
        if (version == 2)
            parameters.put(
                    "source_inspection",
                    new Parameter(ParameterType.STRING, true, 4096, 1, Set.of()));
        return new SyncJobDefinition(
                JOB_ID,
                version,
                L2DailyFeaturesDataset.DEFINITION.datasetId(),
                version,
                "l2_daily_features_owner",
                Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE, Mode.INGEST),
                Mode.INCREMENTAL,
                parameters,
                "file.bounded",
                "l2_daily_features.parquet_date",
                "questdb.full_key_values",
                new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(10)),
                Duration.ofHours(2),
                new Budget(31, 25_000, 25_000, 300_000, L2DailyFeaturesRows.MAX_BATCH_BYTES),
                3,
                List.of(new JobRef("data.l2_dataset_manifest", 1)),
                Frequency.MANUAL,
                ZoneId.of("Asia/Shanghai"),
                true,
                false);
    }

    public Plan plan(
            LocalDate bootstrapFrom,
            LocalDate requestedTo,
            LocalDate logicalDate,
            Mode requestedMode,
            List<String> requestedSymbols)
            throws Exception {
        var definition = currentDefinition();
        Objects.requireNonNull(bootstrapFrom, "Explicit bounded D086 bootstrap date required");
        Objects.requireNonNull(requestedTo, "Explicit D086 end date required");
        Objects.requireNonNull(logicalDate, "D086 logical date required");
        if (requestedTo.isBefore(bootstrapFrom)
                || ChronoUnit.DAYS.between(bootstrapFrom, requestedTo) >= 31)
            throw new IllegalArgumentException(
                    "D086 request must be a nonempty window of at most 31 calendar days");
        var symbols = canonicalSymbols(requestedSymbols);
        Mode mode = requestedMode == null ? definition.defaultMode() : requestedMode;
        if (!definition.supportedModes().contains(mode))
            throw new IllegalArgumentException("Unsupported D086 sync mode");
        String target = targetId();
        var writer = port(this.target, target, true);
        writer.preflight();
        var ledger = new SyncRunLedger(ledgerPath);
        var windows =
                verifiedWindows(ledger, target, source.sourceRootIdentity(), symbols, definition);
        LocalDate previous =
                mode == Mode.INCREMENTAL ? continuousThrough(windows, bootstrapFrom, true) : null;
        // 显式修订只用旧凭证确认目标归属，不把它当作当前完整性证明。
        LocalDate ownedThrough =
                mode == Mode.INCREMENTAL
                        ? previous
                        : continuousThrough(windows, bootstrapFrom, false);
        if (mode == Mode.INCREMENTAL && previous != null && previous.isAfter(requestedTo))
            throw new IllegalArgumentException(
                    "D086 checkpoint exceeds requested end; use bounded RECONCILE");
        LocalDate from = bootstrapFrom;
        if (mode == Mode.INCREMENTAL && previous != null) {
            LocalDate overlap = previous.minusDays(definition.revisionDays() - 1L);
            if (overlap.isAfter(from)) from = overlap;
        }
        if (from.isAfter(requestedTo))
            throw new IllegalArgumentException("D086 incremental window is empty");

        var openDays = DailyTradingSessions.read(calendars, from, requestedTo);
        var inspection =
                source.inspect(
                        from,
                        requestedTo,
                        symbols,
                        definition.budget().maxRows(),
                        definition.budget().maxSlices());
        if (!inspection.dates().equals(openDays))
            throw new IllegalStateException(
                    "D086 source partitions do not cover the registered SSE open sessions");

        var existingDates = writer.readExistingDates();
        LocalDate latestTargetDate =
                existingDates.stream().max(Comparator.naturalOrder()).orElse(null);
        if (latestTargetDate != null
                && (ownedThrough == null || latestTargetDate.isAfter(ownedThrough)))
            throw new IllegalStateException(
                    "D086 isolated target contains data beyond its verified ledger checkpoint");

        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("source_root_id", source.sourceRootIdentity());
        if (!symbols.isEmpty()) parameters.put("symbols", symbols);
        if (definition.version() == 2)
            parameters.put("source_inspection", JSON.writeValueAsString(inspection));
        FrozenRequest request = definition.freeze(mode, parameters, from, requestedTo, logicalDate);
        return new Plan(request, target, bootstrapFrom, previous, existingDates.size(), inspection);
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception {
        return execute(plan, null);
    }

    public SyncJobRunner.Result resume(Plan plan, String previousRunId) throws Exception {
        if (previousRunId == null || !previousRunId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))
            throw new IllegalArgumentException("Exact prior D086 run ID required");
        return execute(plan, previousRunId);
    }

    public void createIsolatedTarget(String table) {
        target.createIsolatedTarget(table);
    }

    public String targetId() throws Exception {
        return target.targetId();
    }

    public Status status(String runId) throws Exception {
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        var run = ledger.getRun(runId);
        if (!JOB_ID.equals(run.jobId()) || run.jobVersion() < 1 || run.jobVersion() > 2)
            throw new IllegalArgumentException("Run does not belong to D086");
        var state = ledger.get(runId);
        int verifiedSlices = 0, unresolvedSlices = 0, sourceRows = 0, verifiedRows = 0;
        var errors = new HashSet<String>();
        String cursor = null;
        while (true) {
            var entries = ledger.entries(runId, cursor, 1000);
            if (entries.isEmpty()) break;
            for (var entry : entries) {
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                if (entry.state() == SyncRunState.VERIFIED
                        || entry.state() == SyncRunState.VERIFIED_EMPTY) {
                    verifiedSlices++;
                    JsonNode payload = JSON.readTree(entry.payloadJson());
                    int count =
                            payload.path("verification")
                                    .path("expectedRows")
                                    .asInt(payload.path("returnedRows").asInt(0));
                    sourceRows = Math.addExact(sourceRows, Math.max(0, count));
                    verifiedRows = Math.addExact(verifiedRows, Math.max(0, count));
                } else {
                    unresolvedSlices++;
                    JsonNode payload = JSON.readTree(entry.payloadJson());
                    String code = payload.path("errorCode").asText("");
                    if (!code.isBlank()) errors.add(code);
                }
            }
            cursor = entries.getLast().id();
        }
        return new Status(
                runId,
                state.state(),
                run.targetId(),
                run.logicalDate(),
                sourceRows,
                verifiedRows,
                verifiedSlices,
                unresolvedSlices,
                errors.stream().sorted().toList());
    }

    public boolean cancel(String runId) throws Exception {
        var ledger = SyncRunLedger.openExisting(ledgerPath);
        var run = ledger.getRun(runId);
        if (!JOB_ID.equals(run.jobId()) || run.jobVersion() < 1 || run.jobVersion() > 2)
            throw new IllegalArgumentException("Run does not belong to D086");
        return ledger.requestCancellation(runId);
    }

    private SyncJobDefinition currentDefinition() {
        return definition(L2DailyFeaturesTarget.versionOf(target));
    }

    private FrozenRunRequest.Restored saved(String prior) throws Exception {
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        var run = ledger.getRun(prior);
        if (run == null || !JOB_ID.equals(run.jobId()))
            throw new IllegalArgumentException("原记录不是 L2 日特征任务");
        var original = definition(run.jobVersion());
        var state = ledger.get(prior).state();
        return state == SyncRunState.VERIFIED || state == SyncRunState.VERIFIED_EMPTY
                ? FrozenRunRequest.restoreCompleted(ledgerPath, prior, original)
                : FrozenRunRequest.restore(ledgerPath, prior, original);
    }

    private L2DailyFeaturesParquetSource.Inspection inspection(FrozenRequest request)
            throws Exception {
        if (request.from() == null || request.to() == null)
            throw new IllegalArgumentException("L2 原窗口缺失");
        if (!source.sourceRootIdentity().equals(request.parameters().get("source_root_id")))
            throw new IllegalStateException("L2 原来源根已变化");
        if (request.definition().version() == 1) {
            // v1 没有冻结完整 inspection，只按原范围读取，执行前另核对已有原页面。
            return source.inspect(
                    request.from(),
                    request.to(),
                    readSymbols(JSON.valueToTree(request.parameters().get("symbols"))),
                    request.definition().budget().maxRows(),
                    request.definition().budget().maxSlices());
        }
        String original = (String) request.parameters().get("source_inspection");
        var value = JSON.readValue(original, L2DailyFeaturesParquetSource.Inspection.class);
        if (!original.equals(JSON.writeValueAsString(value))
                || !value.from().equals(request.from())
                || !value.to().equals(request.to())
                || !value.rootIdentity().equals(request.parameters().get("source_root_id")))
            throw new IllegalStateException("L2 原 inspection 不匹配冻结请求");
        return value;
    }

    /** 不读取当前日历、目标范围或检查点，不改原请求及来源字段。 */
    public Plan restorePlan(String prior) throws Exception {
        var restored = saved(prior);
        var request = restored.request();
        var original =
                L2DailyFeaturesTarget.forFrozenVersion(
                        target, request.definition().datasetVersion());
        if (!restored.targetId().equals(original.targetId()))
            throw new IllegalStateException("L2 原物理目标已变化");
        return new Plan(request, restored.targetId(), request.from(), null, 0, inspection(request));
    }

    public SyncJobRunner.Result resume(String prior) throws Exception {
        return resume(restorePlan(prior), prior);
    }

    /** 新鲜核对完整来源、所有值、范围数量及稳定版本；不写目标或推进原账本。 */
    public String revalidate(String prior) throws Exception {
        var restored = saved(prior);
        var request = restored.request();
        var original =
                L2DailyFeaturesTarget.forFrozenVersion(
                        target, request.definition().datasetVersion());
        return CompletedSourceRevalidation.<L2DailyFeatures, L2DailyFeaturesKey>revalidate(
                ledgerPath,
                prior,
                request.definition(),
                original::targetId,
                (evidence, identity) ->
                        new L2DailyFeaturesSyncAdapter(
                                source,
                                inspection(request),
                                readSymbols(JSON.valueToTree(request.parameters().get("symbols"))),
                                request.definition(),
                                port(original, identity, false),
                                true));
    }

    private SyncJobRunner.Result execute(Plan plan, String previousRunId) throws Exception {
        Objects.requireNonNull(plan);
        long executionStart = System.nanoTime();
        var request = plan.request();
        BooleanSupplier timedOut =
                () ->
                        Thread.currentThread().isInterrupted()
                                || System.nanoTime() - executionStart
                                        >= request.definition().timeout().toNanos();
        if (!request.definition().equals(definition(request.definition().version())))
            throw new IllegalStateException("L2 原定义不是 canonical 版本");
        if (previousRunId == null && !request.definition().equals(currentDefinition()))
            throw new IllegalStateException("L2 新运行必须匹配当前布局");
        if (previousRunId != null) {
            var restored = saved(previousRunId);
            if (!plan.targetId().equals(restored.targetId())
                    || !SyncRequestIdentity.fingerprint(request, plan.targetId())
                            .equals(
                                    SyncRequestIdentity.fingerprint(
                                            restored.request(), restored.targetId())))
                throw new IllegalStateException("L2 恢复请求不能重新规划");
        }
        var original =
                L2DailyFeaturesTarget.forFrozenVersion(
                        target, request.definition().datasetVersion());
        if (!original.targetId().equals(plan.targetId()))
            throw new IllegalStateException("L2 原物理目标已变化");
        var symbols =
                canonicalSymbols(
                        (List<String>) request.parameters().getOrDefault("symbols", List.of()));
        var writer = port(original, plan.targetId(), true);
        var adapter =
                new L2DailyFeaturesSyncAdapter(
                        source,
                        plan.source(),
                        symbols,
                        request.definition(),
                        writer,
                        request.definition().version() == 2 || request.mode() == Mode.RECONCILE);
        if (previousRunId != null) verifyOriginalPages(plan, previousRunId, symbols, timedOut);
        var ledger = new SyncRunLedger(ledgerPath);
        var runner =
                new SyncJobRunner<L2DailyFeatures, L2DailyFeaturesKey>(
                        ledger, new DatasetIntervalLock(ledgerPath));
        String runId =
                "d086-"
                        + RUN_STAMP.format(Instant.now().atZone(ZoneId.of("UTC")))
                        + "-"
                        + UUID.randomUUID();
        BooleanSupplier cancelled =
                () -> {
                    if (timedOut.getAsBoolean()) return true;
                    try {
                        return ledger.cancellationRequested(runId);
                    } catch (SQLException failure) {
                        throw new IllegalStateException(
                                "Cannot read D086 cancellation state", failure);
                    }
                };
        return previousRunId == null
                ? runner.run(runId, null, plan.targetId(), request, adapter, cancelled)
                : runner.resume(runId, previousRunId, plan.targetId(), request, adapter, cancelled);
    }

    private L2DailyFeaturesWriteSession port(
            L2DailyFeaturesTarget original, String identity, boolean writable) {
        return new L2DailyFeaturesBoundSession(original, identity, writable);
    }

    private void verifyOriginalPages(
            Plan plan, String prior, List<String> symbols, BooleanSupplier stopped)
            throws Exception {
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        var fingerprints = new HashSet<String>();
        String cursor = null;
        long seen = 0;
        while (true) {
            var entries = ledger.entries(prior, cursor, 1000);
            if (entries.isEmpty()) break;
            for (var entry : entries) {
                if (++seen > plan.request().definition().budget().maxSlices() + 2L)
                    throw new IllegalStateException("L2 原页面超过预算");
                if (!entry.state().terminal()) throw new IllegalStateException("L2 原子项仍未决");
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                for (var event : ledger.events(entry.id(), -1, 1000))
                    if (event.state() == SyncRunState.FETCHED) {
                        String fingerprint =
                                JSON.readTree(event.payloadJson())
                                        .path("sourceFingerprint")
                                        .asText("");
                        if (fingerprint.isBlank() || !fingerprints.add(fingerprint))
                            throw new IllegalStateException("L2 原页面摘要缺失或重复");
                    }
            }
            cursor = entries.getLast().id();
        }
        if (fingerprints.isEmpty()) return;
        // v1 的原页面也不能被当前文件替换；完整来源摘要变化会改变所有页面指纹。
        var remaining = new HashSet<>(fingerprints);
        var completion =
                source.stream(
                        plan.source(),
                        symbols,
                        plan.request().definition().budget().maxRows(),
                        plan.request().definition().budget().maxSlices(),
                        page -> remaining.remove(page.sourceFingerprint()),
                        stopped,
                        plan.request().definition().timeout());
        if (completion == null || !completion.complete() || !remaining.isEmpty())
            throw new IllegalStateException("L2 原来源页面已变化，不能按旧运行重发");
    }

    private record VerifiedWindow(String runId, FrozenRequest request, Instant verifiedAt) {}

    private List<VerifiedWindow> verifiedWindows(
            SyncRunLedger ledger,
            String identity,
            String sourceRootId,
            List<String> symbols,
            SyncJobDefinition selected)
            throws Exception {
        var windows = new ArrayList<VerifiedWindow>();
        String cursor = null;
        int pages = 0;
        while (true) {
            var history = ledger.history(JOB_ID, cursor, 1000);
            if (history.isEmpty()) break;
            for (var summary : history) {
                if (!identity.equals(summary.targetId())
                        || summary.state() != SyncRunState.VERIFIED) continue;
                var run = ledger.getRun(summary.id());
                if (run.jobVersion() != selected.version()) continue;
                var restored =
                        FrozenRunRequest.restoreCompleted(ledgerPath, summary.id(), selected);
                var request = restored.request();
                if (!sourceRootId.equals(request.parameters().get("source_root_id"))
                        || !symbols.equals(
                                readSymbols(JSON.valueToTree(request.parameters().get("symbols")))))
                    continue;
                // 先核对原子项和页面证明，成功状态本身不能赋予目标归属或完整性。
                VerifiedSliceRecovery.load(ledger, summary.id(), request, identity);
                windows.add(
                        new VerifiedWindow(
                                summary.id(), request, Instant.parse(summary.updatedAt())));
            }
            cursor = history.getLast().id();
            if (++pages > 100)
                throw new IllegalStateException("D086 ledger exceeds its checkpoint scan bound");
        }
        return windows;
    }

    private LocalDate continuousThrough(
            List<VerifiedWindow> candidates, LocalDate anchor, boolean recheck) throws Exception {
        var windows = new ArrayList<>(candidates);
        windows.sort(Comparator.comparing(value -> value.request().from()));
        var active = new ArrayList<VerifiedWindow>();
        var rejected = new HashSet<String>();
        var receipts = new ArrayList<String>();
        var rejectedRuns = new ArrayList<String>();
        LocalDate boundary = anchor, through = null;
        int index = 0;
        Exception lastFailure = null;
        while (true) {
            while (index < windows.size() && !windows.get(index).request().from().isAfter(boundary))
                active.add(windows.get(index++));
            LocalDate at = boundary;
            active.removeIf(value -> value.request().to().isBefore(at));
            active.sort(
                    Comparator.<VerifiedWindow, LocalDate>comparing(value -> value.request().to())
                            .reversed()
                            .thenComparing(VerifiedWindow::verifiedAt, Comparator.reverseOrder()));
            VerifiedWindow best = null;
            for (var candidate : active) {
                if (rejected.contains(candidate.runId())) continue;
                if (!recheck) {
                    best = candidate;
                    break;
                }
                try {
                    receipts.add(revalidate(candidate.runId()));
                    best = candidate;
                    break;
                } catch (CancellationException failure) {
                    throw failure;
                } catch (Exception failure) {
                    if (Thread.currentThread().isInterrupted()) throw failure;
                    // 旧范围可因来源修订而失效；只用另一个独立通过的当前证明接替，不改旧账本。
                    rejected.add(candidate.runId());
                    rejectedRuns.add(candidate.runId());
                    lastFailure = failure;
                }
            }
            if (best == null) {
                if (recheck && !active.isEmpty() && lastFailure != null) throw lastFailure;
                break;
            }
            through = best.request().to();
            if (through.equals(LocalDate.MAX)) break;
            boundary = through.plusDays(1);
        }
        if (recheck && through != null) {
            var report =
                    ledgerPath
                            .getParent()
                            .resolve("sync-evidence")
                            .resolve("l2-checkpoint-" + UUID.randomUUID() + ".json");
            FileEvidenceStore.writeNewUtf8(
                    report,
                    JSON.writeValueAsString(
                            Map.of(
                                    "schema",
                                    "java.l2_daily_features_checkpoint.v2",
                                    "anchor",
                                    anchor,
                                    "through",
                                    through,
                                    "sourceRevalidations",
                                    receipts,
                                    "rejectedOriginalRuns",
                                    rejectedRuns,
                                    "passed",
                                    true)));
        }
        return through;
    }

    private static List<String> readSymbols(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray())
            throw new IllegalStateException("D086 frozen symbol filter is malformed");
        var result = new ArrayList<String>();
        node.forEach(
                value -> {
                    if (!value.isTextual())
                        throw new IllegalStateException("D086 frozen symbol is not text");
                    result.add(value.textValue());
                });
        return List.copyOf(result);
    }

    private static List<String> canonicalSymbols(List<String> requested) {
        if (requested == null || requested.size() > 1000)
            throw new IllegalArgumentException("D086 symbol list exceeds 1000 entries");
        var symbols =
                requested.stream()
                        .map(
                                value -> {
                                    if (value == null || !value.matches("[0-9]{6}\\.(SH|SZ|BJ)"))
                                        throw new IllegalArgumentException(
                                                "Canonical six-digit D086 symbol required");
                                    return value;
                                })
                        .distinct()
                        .sorted()
                        .toList();
        if (symbols.size() != requested.size())
            throw new IllegalArgumentException("Duplicate D086 symbol filter");
        return symbols;
    }
}
