package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.fund.EtfAdjDataset;
import com.zoutrankil.data.domain.shared.policy.IsolatedTablePolicy;
import com.zoutrankil.data.etf.domain.EtfTargetRange;
import com.zoutrankil.data.etf.mapper.EtfAdjMapper;
import com.zoutrankil.data.etf.port.EtfAdjTarget;
import com.zoutrankil.data.etf.port.EtfAdjWriteSession;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.CompletedSourceRevalidation;
import com.zoutrankil.data.service.DailySyncEndDate;
import com.zoutrankil.data.service.FrozenRunRequest;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.SyncRunExecution;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Explicit bounded plan/run/resume/status owner for D015's externally owned etf_adj table. */
@Service
public final class EtfAdjJobService {
    public static final String ISOLATED_TABLE_PREFIX = IsolatedTablePolicy.ETF_ADJ.prefix();

    public record Plan(
            SyncJobDefinition.FrozenRequest request,
            String targetId,
            LocalDate checkpointBefore,
            LocalDate checkpointAnchor,
            LocalDate targetMinDate,
            LocalDate targetMaxDate,
            boolean bootstrap) {
        public Plan {
            Objects.requireNonNull(request);
            Objects.requireNonNull(targetId);
            if (!targetId.equals(request.parameters().get("targetId")))
                throw new IllegalArgumentException(
                        "etf_adj target identity must be frozen in the run request");
            if (!Objects.equals(targetMinDate, request.parameters().get("targetMinBefore"))
                    || !Objects.equals(targetMaxDate, request.parameters().get("targetMaxBefore")))
                throw new IllegalArgumentException(
                        "etf_adj physical range must match its frozen request");
        }
    }

    // 只用于新计划；历史请求的布局必须取自原账本。
    private final CnTradeDateLayout layout;
    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final EtfAdjTradingDates tradingDates;
    private final EtfAdjTarget target;
    private final Path ledgerPath;
    private final String table;

    @Autowired
    public EtfAdjJobService(
            @Lazy SyncJobRegistry jobs,
            TusharePageService pages,
            ExchangeCalendarReadRepository calendars,
            EtfAdjTarget target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(jobs, pages, calendars, target, Path.of(ledgerPath));
    }

    public EtfAdjJobService(
            SyncJobRegistry jobs,
            TusharePageService pages,
            ExchangeCalendarReadRepository calendars,
            EtfAdjTarget target,
            Path ledgerPath) {
        this(jobs, pages, calendars, target, ledgerPath, CnTradeDateLayout.LEGACY_V1);
    }

    public EtfAdjJobService(
            SyncJobRegistry jobs,
            TusharePageService pages,
            ExchangeCalendarReadRepository calendars,
            EtfAdjTarget target,
            Path ledgerPath,
            CnTradeDateLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.jobs = Objects.requireNonNull(jobs);
        this.pages = Objects.requireNonNull(pages);
        this.tradingDates = new EtfAdjTradingDates(calendars);
        this.target = Objects.requireNonNull(target);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
        String table = target.tableName();
        requireAdmittedTableName(table);
        this.table = table;
    }

    public String tableName() {
        return table;
    }

    public static void requireIsolatedTableName(String table) {
        IsolatedTablePolicy.ETF_ADJ.require(table);
    }

    /** Exact external formal target; all other targets retain the isolated namespace requirement. */
    public static void requireAdmittedTableName(String table) {
        EtfAdjDataset.requireExecutionTable(table);
    }

    private void requireAdmittedMode(Mode mode) {
        if ("etf_adj".equals(table) && mode != Mode.BACKFILL && mode != Mode.RECONCILE)
            throw new IllegalArgumentException(
                    "Formal etf_adj requires an explicit bounded BACKFILL/RECONCILE; incremental checkpoints remain receipt-backed isolated behavior");
    }

    public String targetId() throws Exception {
        requireAdmittedTableName(table);
        return target.targetId();
    }

    /** Builds a frozen daily plan. Incremental uses receipt-backed coverage and a five-day revision overlap. */
    public Plan plan(
            Mode requestedMode,
            LocalDate bootstrapFrom,
            LocalDate requestedThrough,
            LocalDate logicalDate)
            throws Exception {
        Objects.requireNonNull(logicalDate, "Frozen etf_adj logical date required");
        if (requestedThrough != null && requestedThrough.isAfter(logicalDate))
            throw new IllegalArgumentException("etf_adj --to exceeds logical date");
        var sourceNow = ZonedDateTime.now(DailySyncEndDate.ZONE);
        LocalDate completedSourceCeiling = DailySyncEndDate.resolve(null, sourceNow);
        LocalDate requestedLimit = requestedThrough == null ? logicalDate : requestedThrough;
        LocalDate resolvedThrough = DailySyncEndDate.resolve(requestedLimit, sourceNow);
        if (resolvedThrough.isAfter(logicalDate)) resolvedThrough = logicalDate;
        SyncJobDefinition definition = EtfAdjSyncJobOwner.definition(layout.schemaVersion());
        Mode mode = requestedMode == null ? definition.defaultMode() : requestedMode;
        if (!definition.supportedModes().contains(mode))
            throw new IllegalArgumentException("Unsupported etf_adj mode");
        requireAdmittedMode(mode);
        if ("etf_adj".equals(table)
                && requestedThrough != null
                && requestedThrough.isAfter(completedSourceCeiling))
            throw new IllegalArgumentException(
                    "Formal etf_adj cannot include an incomplete source date");
        if ((mode == Mode.BACKFILL || mode == Mode.RECONCILE) && requestedThrough == null)
            throw new IllegalArgumentException(
                    "Bounded etf_adj backfill/reconcile requires explicit --to");
        if (mode == Mode.INCREMENTAL
                && bootstrapFrom != null
                && (bootstrapFrom.isAfter(resolvedThrough)
                        || ChronoUnit.DAYS.between(bootstrapFrom, resolvedThrough)
                                >= definition.budget().maxWindowDays()))
            throw new IllegalArgumentException(
                    "Explicit etf_adj bootstrap exceeds the 366-day budget");
        if ((mode == Mode.BACKFILL || mode == Mode.RECONCILE)
                && (bootstrapFrom == null || bootstrapFrom.isAfter(resolvedThrough)))
            throw new IllegalArgumentException(
                    "Bounded etf_adj backfill/reconcile requires explicit --from and --to");
        if (bootstrapFrom != null && bootstrapFrom.isAfter(resolvedThrough))
            throw new IllegalArgumentException("etf_adj --from is after --to");

        String target = targetId();
        var port = writer(target, definition.datasetVersion());
        port.preflight();
        EtfTargetRange physical = readTargetRange(definition.datasetVersion());
        if (physical.max() != null && physical.max().isAfter(logicalDate))
            throw new IllegalStateException(
                    "etf_adj target contains a date after frozen logical date");

        LocalDate from = bootstrapFrom,
                to = resolvedThrough,
                anchor = null,
                checkpointBefore = null;
        boolean bootstrap = false;
        Optional<EtfAdjCoverage.Coverage> saved = Optional.empty();
        if (mode == Mode.INCREMENTAL) {
            saved =
                    EtfAdjCoverage.checkpoint(
                            ledgerPath, target, tradingDates, definition.version());
            if (saved.isEmpty()) {
                if (bootstrapFrom == null)
                    throw new IllegalArgumentException(
                            "Explicit bounded bootstrap --from required without a verified etf_adj checkpoint");
                if (physical.min() != null)
                    throw new IllegalStateException(
                            "Nonempty etf_adj target has no same-target incremental checkpoint; reconcile/inspect it first");
                from = bootstrapFrom;
                anchor = bootstrapFrom;
                bootstrap = true;
            } else {
                if (bootstrapFrom != null)
                    throw new IllegalArgumentException(
                            "--from is bootstrap-only; use BACKFILL for another explicit range");
                var coverage = saved.get();
                checkpointBefore = coverage.through();
                anchor = coverage.anchor();
                if (resolvedThrough.isBefore(checkpointBefore))
                    throw new IllegalArgumentException(
                            "etf_adj end precedes its verified checkpoint");
                from = checkpointBefore.minusDays(definition.revisionDays());
                if (from.isBefore(anchor)) from = anchor;
                if (physical.max() != null && physical.max().isAfter(completedSourceCeiling))
                    throw new IllegalStateException(
                            "etf_adj physical target extends beyond the completed-source ceiling; inspect partial-day rows before incremental catch-up");
                to =
                        physical.max() != null && physical.max().isAfter(resolvedThrough)
                                ? physical.max()
                                : resolvedThrough;
                if (to.isAfter(logicalDate))
                    throw new IllegalStateException("Existing etf_adj target exceeds logical date");
                if (ChronoUnit.DAYS.between(from, to) + 1 > definition.budget().maxWindowDays())
                    throw new IllegalArgumentException(
                            "Resolved etf_adj revision/catch-up window exceeds 366 days");
            }
            EtfAdjCoverage.validateExistingTarget(
                    ledgerPath, saved.orElse(null), target, tradingDates, port);
        } else if (mode == Mode.BACKFILL || mode == Mode.RECONCILE) {
            from = bootstrapFrom;
            if (ChronoUnit.DAYS.between(from, to) + 1 > definition.budget().maxWindowDays())
                throw new IllegalArgumentException(
                        "etf_adj backfill/reconcile exceeds 366-day budget");
        } else
            throw new IllegalArgumentException(
                    "etf_adj supports only bounded incremental, backfill and reconcile");

        var sessions = tradingDates.read(from, to);
        var params = new LinkedHashMap<String, Object>();
        params.put("targetId", target);
        params.put("trade_dates", EtfAdjSyncAdapter.encodeTradeDates(sessions));
        if (mode == Mode.INCREMENTAL) {
            params.put("checkpointAnchor", anchor);
            if (checkpointBefore != null) params.put("checkpointBefore", checkpointBefore);
        }
        if (physical.min() != null) params.put("targetMinBefore", physical.min());
        if (physical.max() != null) params.put("targetMaxBefore", physical.max());
        var request =
                jobs.prepare(
                        definition.jobId(),
                        definition.version(),
                        mode,
                        params,
                        from,
                        to,
                        logicalDate);
        new EtfAdjSyncAdapter(
                        new EtfAdjSource(
                                pages,
                                new EtfAdjMapper(),
                                ledgerPath
                                        .getParent()
                                        .resolve("sync-evidence")
                                        .resolve("etf-adj-preflight")),
                        tradingDates,
                        port,
                        ledgerPath.getParent())
                .preflight(request);
        if (!target.equals(targetId()))
            throw new IllegalStateException("etf_adj target identity changed while planning");
        return new Plan(
                request,
                target,
                checkpointBefore,
                anchor,
                physical.min(),
                physical.max(),
                bootstrap);
    }

    private EtfTargetRange readTargetRange(int datasetVersion) {
        if (target instanceof EtfAdjTarget.Versioned versioned)
            return versioned.range(datasetVersion);
        if (datasetVersion != 1) throw new IllegalArgumentException("旧 ETF 复权目标没有新版范围读取能力");
        return target.range();
    }

    private EtfAdjWriteSession writer(String identity, int datasetVersion) {
        return (EtfAdjWriteSession)
                EtfAdjTarget.writerForFrozenVersion(target, identity, datasetVersion);
    }

    private SyncJobDefinition savedDefinition(String runId) throws Exception {
        var run = SyncRunLedger.openReadOnly(ledgerPath).getRun(Objects.requireNonNull(runId));
        if (run == null || !EtfAdjSyncJobOwner.DEFINITION.jobId().equals(run.jobId()))
            throw new IllegalArgumentException("原记录不是 ETF 复权任务");
        return EtfAdjSyncJobOwner.definition(run.jobVersion());
    }

    private FrozenRunRequest.Restored restore(String runId) throws Exception {
        return FrozenRunRequest.restore(ledgerPath, runId, savedDefinition(runId));
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception {
        return execute("etf-adj-" + UUID.randomUUID(), null, plan);
    }

    public SyncJobRunner.Result resume(String priorRunId) throws Exception {
        var saved = restore(priorRunId);
        var parameters = saved.request().parameters();
        var plan =
                new Plan(
                        saved.request(),
                        saved.targetId(),
                        (LocalDate) parameters.get("checkpointBefore"),
                        (LocalDate) parameters.get("checkpointAnchor"),
                        (LocalDate) parameters.get("targetMinBefore"),
                        (LocalDate) parameters.get("targetMaxBefore"),
                        !parameters.containsKey("checkpointBefore"));
        return resume(plan, priorRunId);
    }

    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        Objects.requireNonNull(priorRunId);
        var saved = restore(priorRunId);
        if (!SyncRequestIdentity.fingerprint(saved.request(), saved.targetId())
                .equals(SyncRequestIdentity.fingerprint(plan.request(), plan.targetId())))
            throw new IllegalArgumentException(
                    "Resume requires the exact saved etf_adj request and target");
        return execute("etf-adj-" + UUID.randomUUID(), priorRunId, plan);
    }

    public SyncRunLedger.Entry status(String runId) throws Exception {
        return SyncRunLedger.openReadOnly(ledgerPath).get(Objects.requireNonNull(runId));
    }

    public List<SyncRunLedger.Entry> entries(String runId, String afterId, int limit)
            throws Exception {
        return SyncRunLedger.openReadOnly(ledgerPath)
                .entries(Objects.requireNonNull(runId), afterId, limit);
    }

    public boolean cancel(String runId) throws Exception {
        return new SyncRunLedger(ledgerPath).requestCancellation(Objects.requireNonNull(runId));
    }

    private SyncJobRunner.Result execute(String runId, String priorRunId, Plan plan)
            throws Exception {
        requireAdmittedMode(plan.request().mode());
        // 新运行必须匹配当前选定版本；恢复允许原版本，但原目标身份仍须一致。
        var expected = EtfAdjSyncJobOwner.definition(plan.request().definition().version());
        if (!plan.request().definition().equals(expected)
                || priorRunId == null
                        && !expected.equals(EtfAdjSyncJobOwner.definition(layout.schemaVersion()))
                || !plan.targetId().equals(plan.request().parameters().get("targetId"))
                || !plan.targetId().equals(targetId()))
            throw new IllegalStateException(
                    "Frozen etf_adj plan or target identity changed before run");
        if (priorRunId == null) validateCurrentBaseline(plan);
        return SyncRunExecution.execute(
                ledgerPath,
                runId,
                priorRunId,
                plan.targetId(),
                plan.request(),
                "Cannot read etf_adj cancellation state",
                () -> {
                    var port =
                            writer(plan.targetId(), plan.request().definition().datasetVersion());
                    var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
                    return new EtfAdjSyncAdapter(
                            new EtfAdjSource(pages, new EtfAdjMapper(), evidence.resolve("source")),
                            tradingDates,
                            port,
                            evidence);
                });
    }

    /** A preview cannot be reused after target rows or the verified incremental baseline have changed. */
    private void validateCurrentBaseline(Plan plan) throws Exception {
        EtfTargetRange current = readTargetRange(plan.request().definition().datasetVersion());
        if (!Objects.equals(current.min(), plan.targetMinDate())
                || !Objects.equals(current.max(), plan.targetMaxDate()))
            throw new IllegalStateException(
                    "etf_adj physical date range changed after plan; create a fresh frozen plan");
        if (plan.request().mode() != Mode.INCREMENTAL) return;
        var saved =
                EtfAdjCoverage.checkpoint(
                        ledgerPath,
                        plan.targetId(),
                        tradingDates,
                        plan.request().definition().version());
        if (saved.isPresent() != (plan.checkpointBefore() != null)
                || saved.isPresent()
                        && (!saved.get().through().equals(plan.checkpointBefore())
                                || !saved.get().anchor().equals(plan.checkpointAnchor())))
            throw new IllegalStateException(
                    "etf_adj verified checkpoint changed after plan; create a fresh frozen plan");
        var port = writer(plan.targetId(), plan.request().definition().datasetVersion());
        EtfAdjCoverage.validateExistingTarget(
                ledgerPath, saved.orElse(null), plan.targetId(), tradingDates, port);
    }

    public String revalidate(String priorRunId) throws Exception {
        var definition = savedDefinition(priorRunId);
        return CompletedSourceRevalidation.revalidate(
                ledgerPath,
                priorRunId,
                definition,
                this::targetId,
                (evidence, identity) ->
                        new EtfAdjSyncAdapter(
                                new EtfAdjSource(
                                        pages, new EtfAdjMapper(), evidence.resolve("source")),
                                tradingDates,
                                writer(identity, definition.datasetVersion()),
                                evidence));
    }

    public Map<String, Object> reconcile(String runId, Path writerLog) throws Exception {
        return EtfAdjRunRecovery.reconcile(ledgerPath, runId, writerLog, target);
    }
}
