package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Budget;
import com.zoutrankil.data.domain.SyncJobDefinition.Frequency;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.JobRef;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncJobDefinition.Parameter;
import com.zoutrankil.data.domain.SyncJobDefinition.ParameterType;
import com.zoutrankil.data.domain.SyncJobDefinition.RetryPolicy;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesDataset;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesKey;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.l2.domain.L2DailyFeaturesRows;
import com.zoutrankil.data.l2.port.L2DailyFeaturesTarget;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DailyTradingSessions;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.SyncJobRunner;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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

    private static final ObjectMapper JSON = new ObjectMapper();
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
        return definition().supportedModes();
    }

    @Override
    public List<SyncJobDefinition> syncJobDefinitions() {
        return List.of(definition());
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
        Objects.requireNonNull(bootstrapFrom, "Explicit bounded D086 bootstrap date required");
        Objects.requireNonNull(requestedTo, "Explicit D086 end date required");
        Objects.requireNonNull(logicalDate, "D086 logical date required");
        if (requestedTo.isBefore(bootstrapFrom)
                || ChronoUnit.DAYS.between(bootstrapFrom, requestedTo) >= 31)
            throw new IllegalArgumentException(
                    "D086 request must be a nonempty window of at most 31 calendar days");
        var symbols = canonicalSymbols(requestedSymbols);
        Mode mode = requestedMode == null ? definition().defaultMode() : requestedMode;
        if (!definition().supportedModes().contains(mode))
            throw new IllegalArgumentException("Unsupported D086 sync mode");
        String target = targetId();
        var writer = port();
        writer.preflight();
        var ledger = new SyncRunLedger(ledgerPath);
        LocalDate previous =
                latestVerifiedThrough(ledger, target, source.sourceRootIdentity(), symbols);
        if (mode == Mode.INCREMENTAL && previous != null && previous.isAfter(requestedTo))
            throw new IllegalArgumentException(
                    "D086 checkpoint exceeds requested end; use bounded RECONCILE");
        LocalDate from = bootstrapFrom;
        if (mode == Mode.INCREMENTAL && previous != null) {
            LocalDate overlap = previous.minusDays(definition().revisionDays() - 1L);
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
                        definition().budget().maxRows(),
                        definition().budget().maxSlices());
        if (!inspection.dates().equals(openDays))
            throw new IllegalStateException(
                    "D086 source partitions do not cover the registered SSE open sessions");

        var existingDates = writer.readExistingDates();
        LocalDate latestTargetDate =
                existingDates.stream().max(Comparator.naturalOrder()).orElse(null);
        if (latestTargetDate != null && (previous == null || latestTargetDate.isAfter(previous)))
            throw new IllegalStateException(
                    "D086 isolated target contains data beyond its verified ledger checkpoint");

        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("source_root_id", source.sourceRootIdentity());
        if (!symbols.isEmpty()) parameters.put("symbols", symbols);
        FrozenRequest request =
                definition().freeze(mode, parameters, from, requestedTo, logicalDate);
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
        var ledger = new SyncRunLedger(ledgerPath);
        var run = ledger.getRun(runId);
        if (!JOB_ID.equals(run.jobId()) || run.jobVersion() != definition().version())
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
        var ledger = new SyncRunLedger(ledgerPath);
        var run = ledger.getRun(runId);
        if (!JOB_ID.equals(run.jobId()))
            throw new IllegalArgumentException("Run does not belong to D086");
        return ledger.requestCancellation(runId);
    }

    private SyncJobRunner.Result execute(Plan plan, String previousRunId) throws Exception {
        Objects.requireNonNull(plan);
        if (!plan.request().definition().equals(definition())
                || !targetId().equals(plan.targetId()))
            throw new IllegalStateException(
                    "D086 definition or isolated target changed since planning");
        var parameters = plan.request().parameters();
        var symbols =
                canonicalSymbols((List<String>) parameters.getOrDefault("symbols", List.of()));
        var writer = port();
        var adapter =
                new L2DailyFeaturesSyncAdapter(
                        source, plan.source(), symbols, definition(), writer);
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
                    if (Thread.currentThread().isInterrupted()) return true;
                    try {
                        return ledger.cancellationRequested(runId);
                    } catch (SQLException failure) {
                        throw new IllegalStateException(
                                "Cannot read D086 cancellation state", failure);
                    }
                };
        return previousRunId == null
                ? runner.run(runId, null, plan.targetId(), plan.request(), adapter, cancelled)
                : runner.resume(
                        runId, previousRunId, plan.targetId(), plan.request(), adapter, cancelled);
    }

    private L2DailyFeaturesWriteSession port() {
        var writer = target.newWriter();
        // 当前控制入口尚未按版本恢复，不能提前把新版目标交给原 v1 计划。
        if (L2DailyFeaturesTarget.versionOf(target) != 1
                || writer instanceof L2DailyFeaturesWriteSession.Versioned selected
                        && selected.datasetVersion() != 1)
            throw new IllegalArgumentException("当前 L2 日特征控制入口只接 v1");
        return writer;
    }

    private LocalDate latestVerifiedThrough(
            SyncRunLedger ledger, String target, String sourceRootId, List<String> symbols)
            throws Exception {
        LocalDate latest = null;
        String cursor = null;
        int pages = 0;
        while (true) {
            var history = ledger.history(JOB_ID, cursor, 1000);
            if (history.isEmpty()) break;
            for (var summary : history) {
                if (!target.equals(summary.targetId()) || summary.state() != SyncRunState.VERIFIED)
                    continue;
                var run = ledger.getRun(summary.id());
                JsonNode parameters = JSON.readTree(run.frozenJson()).path("parameters");
                if (!sourceRootId.equals(parameters.path("source_root_id").asText(""))
                        || !symbols.equals(readSymbols(parameters.get("symbols")))) continue;
                String end = JSON.readTree(run.frozenJson()).path("to").asText("");
                if (!end.isBlank()) {
                    LocalDate day = LocalDate.parse(end);
                    if (latest == null || day.isAfter(latest)) latest = day;
                }
            }
            cursor = history.getLast().id();
            if (++pages > 100)
                throw new IllegalStateException("D086 ledger exceeds its checkpoint scan bound");
        }
        return latest;
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
