package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.fund.EtfShare;
import com.zoutrankil.data.domain.fund.EtfShareDataset;
import com.zoutrankil.data.domain.fund.EtfShareKey;
import com.zoutrankil.data.etf.domain.EtfTargetRange;
import com.zoutrankil.data.etf.mapper.EtfShareMapper;
import com.zoutrankil.data.etf.port.EtfShareTarget;
import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.etf.port.EtfWriteSession;
import com.zoutrankil.data.repository.SyncRunLedger;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Explicit bounded plan/run/resume/status owner for the externally owned etf_share table. */
@Service
public final class EtfShareJobService {
    public static final String ISOLATED_TABLE_PREFIX = EtfShareDataset.ISOLATED_PREFIX;

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
                        "etf_share target identity must be frozen in the run request");
            if (!Objects.equals(targetMinDate, request.parameters().get("targetMinBefore"))
                    || !Objects.equals(targetMaxDate, request.parameters().get("targetMaxBefore")))
                throw new IllegalArgumentException(
                        "etf_share physical range must match its frozen request");
        }
    }

    // 当前布局只用于新计划；恢复从原账本选择作业版本。
    private final CnTradeDateLayout layout;
    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final EtfShareTradingDates tradingDates;
    private final EtfTarget<EtfShare, EtfShareKey> target;
    private final Path ledgerPath;
    private final String table;

    public EtfShareJobService(
            @Lazy SyncJobRegistry jobs,
            TusharePageService pages,
            ExchangeCalendarReadRepository calendars,
            EtfTarget<EtfShare, EtfShareKey> target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(jobs, pages, calendars, target, Path.of(ledgerPath));
    }

    @Autowired
    public EtfShareJobService(
            @Lazy SyncJobRegistry jobs,
            TusharePageService pages,
            ExchangeCalendarReadRepository calendars,
            EtfTarget<EtfShare, EtfShareKey> target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            @Value("${app.sync.etf-share-schema-version:1}") int version) {
        this(
                jobs,
                pages,
                calendars,
                target,
                Path.of(ledgerPath),
                CnTradeDateLayout.fromVersion(version));
    }

    public EtfShareJobService(
            SyncJobRegistry jobs,
            TusharePageService pages,
            ExchangeCalendarReadRepository calendars,
            EtfTarget<EtfShare, EtfShareKey> target,
            Path ledgerPath) {
        this(jobs, pages, calendars, target, ledgerPath, CnTradeDateLayout.LEGACY_V1);
    }

    public EtfShareJobService(
            SyncJobRegistry jobs,
            TusharePageService pages,
            ExchangeCalendarReadRepository calendars,
            EtfTarget<EtfShare, EtfShareKey> target,
            Path ledgerPath,
            CnTradeDateLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.jobs = Objects.requireNonNull(jobs);
        this.pages = Objects.requireNonNull(pages);
        this.tradingDates = new EtfShareTradingDates(calendars);
        this.target = Objects.requireNonNull(target);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
        String table = target.tableName();
        requireIsolatedTableName(table);
        this.table = table;
    }

    public String tableName() {
        return table;
    }

    public static void requireIsolatedTableName(String table) {
        DatasetDefinition.identifier(table);
        EtfShareDataset.requireIsolatedTable(table);
    }

    public String targetId() throws Exception {
        requireIsolatedTableName(table);
        return target.targetId();
    }

    /** Builds a frozen daily plan. Incremental uses receipt-backed coverage and a five-day revision overlap. */
    public Plan plan(
            Mode requestedMode,
            LocalDate bootstrapFrom,
            LocalDate requestedThrough,
            LocalDate logicalDate)
            throws Exception {
        Objects.requireNonNull(logicalDate, "Frozen etf_share logical date required");
        if (requestedThrough != null && requestedThrough.isAfter(logicalDate))
            throw new IllegalArgumentException("etf_share --to exceeds logical date");
        var sourceNow = ZonedDateTime.now(DailySyncEndDate.ZONE);
        LocalDate completedSourceCeiling = DailySyncEndDate.resolve(null, sourceNow);
        LocalDate requestedLimit = requestedThrough == null ? logicalDate : requestedThrough;
        LocalDate resolvedThrough = DailySyncEndDate.resolve(requestedLimit, sourceNow);
        if (resolvedThrough.isAfter(logicalDate)) resolvedThrough = logicalDate;
        SyncJobDefinition definition = EtfShareSyncJobOwner.forLayout(layout.schemaVersion());
        Mode mode = requestedMode == null ? definition.defaultMode() : requestedMode;
        if (!definition.supportedModes().contains(mode))
            throw new IllegalArgumentException("Unsupported etf_share mode");
        if ((mode == Mode.BACKFILL || mode == Mode.RECONCILE) && requestedThrough == null)
            throw new IllegalArgumentException(
                    "Bounded etf_share backfill/reconcile requires explicit --to");
        if (mode == Mode.INCREMENTAL
                && bootstrapFrom != null
                && (bootstrapFrom.isAfter(resolvedThrough)
                        || ChronoUnit.DAYS.between(bootstrapFrom, resolvedThrough)
                                >= definition.budget().maxWindowDays()))
            throw new IllegalArgumentException(
                    "Explicit etf_share bootstrap exceeds the 366-day budget");
        if ((mode == Mode.BACKFILL || mode == Mode.RECONCILE)
                && (bootstrapFrom == null || bootstrapFrom.isAfter(resolvedThrough)))
            throw new IllegalArgumentException(
                    "Bounded etf_share backfill/reconcile requires explicit --from and --to");
        if (bootstrapFrom != null && bootstrapFrom.isAfter(resolvedThrough))
            throw new IllegalArgumentException("etf_share --from is after --to");

        String target = targetId();
        var port = writer(target, definition.datasetVersion());
        port.preflight();
        EtfTargetRange physical = readTargetRange(definition.datasetVersion());
        if (physical.max() != null && physical.max().isAfter(logicalDate))
            throw new IllegalStateException(
                    "etf_share target contains a date after frozen logical date");

        LocalDate from = bootstrapFrom,
                to = resolvedThrough,
                anchor = null,
                checkpointBefore = null;
        boolean bootstrap = false;
        Optional<EtfShareCoverage.Coverage> saved = Optional.empty();
        if (mode == Mode.INCREMENTAL) {
            saved =
                    EtfShareCoverage.checkpoint(
                            ledgerPath, target, tradingDates, definition.version());
            if (saved.isEmpty()) {
                if (bootstrapFrom == null)
                    throw new IllegalArgumentException(
                            "Explicit bounded bootstrap --from required without a verified etf_share checkpoint");
                if (physical.min() != null)
                    throw new IllegalStateException(
                            "Nonempty etf_share target has no same-target incremental checkpoint; reconcile/inspect it first");
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
                            "etf_share end precedes its verified checkpoint");
                from = checkpointBefore.minusDays(definition.revisionDays());
                if (from.isBefore(anchor)) from = anchor;
                if (physical.max() != null && physical.max().isAfter(completedSourceCeiling))
                    throw new IllegalStateException(
                            "etf_share physical target extends beyond the completed-source ceiling; inspect partial-day rows before incremental catch-up");
                to =
                        physical.max() != null && physical.max().isAfter(resolvedThrough)
                                ? physical.max()
                                : resolvedThrough;
                if (to.isAfter(logicalDate))
                    throw new IllegalStateException(
                            "Existing etf_share target exceeds logical date");
                if (ChronoUnit.DAYS.between(from, to) + 1 > definition.budget().maxWindowDays())
                    throw new IllegalArgumentException(
                            "Resolved etf_share revision/catch-up window exceeds 366 days");
            }
            EtfShareCoverage.validateExistingTarget(
                    ledgerPath, saved.orElse(null), target, tradingDates, port);
        } else if (mode == Mode.BACKFILL || mode == Mode.RECONCILE) {
            from = bootstrapFrom;
            if (ChronoUnit.DAYS.between(from, to) + 1 > definition.budget().maxWindowDays())
                throw new IllegalArgumentException(
                        "etf_share backfill/reconcile exceeds 366-day budget");
        } else
            throw new IllegalArgumentException(
                    "etf_share supports only bounded incremental, backfill and reconcile");

        var sessions = tradingDates.read(from, to);
        var params = new LinkedHashMap<String, Object>();
        params.put("targetId", target);
        params.put("trade_dates", EtfShareSyncAdapter.encodeTradeDates(sessions));
        if (mode == Mode.INCREMENTAL) {
            params.put("checkpointAnchor", anchor);
            if (checkpointBefore != null) params.put("checkpointBefore", checkpointBefore);
        }
        if (physical.min() != null) params.put("targetMinBefore", physical.min());
        if (physical.max() != null) params.put("targetMaxBefore", physical.max());
        // 新计划固定来源观察时刻；恢复沿用原 observedAt，不重新生成 update_time。
        params.put("observedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        var request =
                jobs.prepare(
                        definition.jobId(),
                        definition.version(),
                        mode,
                        params,
                        from,
                        to,
                        logicalDate);
        new EtfShareSyncAdapter(
                        new EtfShareSource(
                                pages,
                                new EtfShareMapper(),
                                ledgerPath
                                        .getParent()
                                        .resolve("sync-evidence")
                                        .resolve("etf-share-preflight")),
                        tradingDates,
                        port,
                        ledgerPath.getParent())
                .preflight(request);
        if (!target.equals(targetId()))
            throw new IllegalStateException("etf_share target identity changed while planning");
        return new Plan(
                request,
                target,
                checkpointBefore,
                anchor,
                physical.min(),
                physical.max(),
                bootstrap);
    }

    private EtfTargetRange readTargetRange(int version) {
        if (target instanceof EtfShareTarget.Versioned selected) return selected.range(version);
        if (version != 1) throw new IllegalArgumentException("旧 ETF 份额目标没有新版范围读取能力");
        return target.range();
    }

    private EtfWriteSession<EtfShare, EtfShareKey> writer(String identity, int version) {
        return EtfShareTarget.writerForFrozenVersion(target, identity, version);
    }

    private FrozenRunRequest.Restored restore(String runId) throws Exception {
        var saved = SyncRunLedger.openReadOnly(ledgerPath).getRun(Objects.requireNonNull(runId));
        if (saved == null || !EtfShareSyncJobOwner.DEFINITION.jobId().equals(saved.jobId()))
            throw new IllegalArgumentException("原记录不是 ETF 份额任务");
        return FrozenRunRequest.restore(
                ledgerPath, runId, EtfShareSyncJobOwner.definition(saved.jobVersion()));
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception {
        return execute("etf-share-" + UUID.randomUUID(), null, plan);
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
                        saved.request().mode() == Mode.INCREMENTAL
                                && !parameters.containsKey("checkpointBefore"));
        return resume(plan, priorRunId);
    }

    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        Objects.requireNonNull(priorRunId);
        var saved = restore(priorRunId);
        if (!SyncRequestIdentity.fingerprint(saved.request(), saved.targetId())
                .equals(SyncRequestIdentity.fingerprint(plan.request(), plan.targetId())))
            throw new IllegalArgumentException(
                    "Resume requires the exact saved etf_share request and target");
        return execute("etf-share-" + UUID.randomUUID(), priorRunId, plan);
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
        var expected = EtfShareSyncJobOwner.definition(plan.request().definition().version());
        if (!plan.request().definition().equals(expected)
                || priorRunId == null
                        && !expected.equals(EtfShareSyncJobOwner.forLayout(layout.schemaVersion()))
                || !plan.targetId().equals(plan.request().parameters().get("targetId"))
                || !plan.targetId().equals(targetId()))
            throw new IllegalStateException(
                    "Frozen etf_share plan or target identity changed before run");
        if (priorRunId == null) validateCurrentBaseline(plan);
        return SyncRunExecution.execute(
                ledgerPath,
                runId,
                priorRunId,
                plan.targetId(),
                plan.request(),
                "Cannot read etf_share cancellation state",
                () -> {
                    var port =
                            writer(plan.targetId(), plan.request().definition().datasetVersion());
                    var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
                    return new EtfShareSyncAdapter(
                            new EtfShareSource(
                                    pages, new EtfShareMapper(), evidence.resolve("source")),
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
                    "etf_share physical date range changed after plan; create a fresh frozen plan");
        if (plan.request().mode() != Mode.INCREMENTAL) return;
        var saved =
                EtfShareCoverage.checkpoint(
                        ledgerPath,
                        plan.targetId(),
                        tradingDates,
                        plan.request().definition().version());
        if (saved.isPresent() != (plan.checkpointBefore() != null)
                || saved.isPresent()
                        && (!saved.get().through().equals(plan.checkpointBefore())
                                || !saved.get().anchor().equals(plan.checkpointAnchor())))
            throw new IllegalStateException(
                    "etf_share verified checkpoint changed after plan; create a fresh frozen plan");
        var port = writer(plan.targetId(), plan.request().definition().datasetVersion());
        EtfShareCoverage.validateExistingTarget(
                ledgerPath, saved.orElse(null), plan.targetId(), tradingDates, port);
    }
}
