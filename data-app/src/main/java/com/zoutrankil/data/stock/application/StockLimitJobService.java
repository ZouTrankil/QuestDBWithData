package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.stock.domain.StockExecutionTables;

import com.zoutrankil.data.stock.port.StockLimitTarget;
import com.zoutrankil.data.stock.domain.StockTargetRange;

import com.zoutrankil.data.stock.mapper.StockLimitMapper;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.policy.IsolatedTablePolicy;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Explicit bounded plan/run/resume/status owner for D010's externally owned stk_limit table. */
@Service
public final class StockLimitJobService {
    public static final String ISOLATED_TABLE_PREFIX = IsolatedTablePolicy.STOCK_LIMIT.prefix();
    public record Plan(SyncJobDefinition.FrozenRequest request, String targetId, LocalDate checkpointBefore,
                       LocalDate checkpointAnchor, LocalDate targetMinDate, LocalDate targetMaxDate,
                       boolean bootstrap) {
        public Plan {
            Objects.requireNonNull(request); Objects.requireNonNull(targetId);
            if (!targetId.equals(request.parameters().get("targetId")))
                throw new IllegalArgumentException("stk_limit target identity must be frozen in the run request");
        }
    }

    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final StockLimitTradingDates tradingDates;
    private final StockLimitTarget target;
    private final Path ledgerPath;
    private final String table;

    @Autowired
    public StockLimitJobService(@Lazy SyncJobRegistry jobs, TusharePageService pages,
            ExchangeCalendarReadRepository calendars, StockLimitTarget target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(jobs, pages, calendars, target, Path.of(ledgerPath));
    }
    public StockLimitJobService(SyncJobRegistry jobs, TusharePageService pages,
            ExchangeCalendarReadRepository calendars, StockLimitTarget target, Path ledgerPath) {
        this.jobs = Objects.requireNonNull(jobs); this.pages = Objects.requireNonNull(pages);
        this.tradingDates = new StockLimitTradingDates(calendars); this.target = Objects.requireNonNull(target);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
        String table = target.tableName(); requireExecutionTableName(table); this.table = table;
    }

    public String tableName() { return table; }

    public static void requireIsolatedTableName(String table) {
        IsolatedTablePolicy.STOCK_LIMIT.require(table);
    }

    /** Existing formal table is allowed only through its bounded recovery owner; no formal DDL is issued. */
    public static void requireExecutionTableName(String table) {
        StockExecutionTables.requireStockLimit(table);
    }

    private void requireExecutionMode(Mode mode) {
        if ("stk_limit".equals(table) && mode != Mode.BACKFILL && mode != Mode.RECONCILE)
            throw new IllegalArgumentException("Formal stk_limit requires explicit bounded BACKFILL or RECONCILE; old rows do not establish incremental coverage");
    }

    public String targetId() throws Exception { requireExecutionTableName(table); return target.targetId(); }

    /** Builds a frozen daily plan. Incremental uses receipt-backed coverage and a five-day revision overlap. */
    public Plan plan(Mode requestedMode, LocalDate bootstrapFrom, LocalDate requestedThrough, LocalDate logicalDate) throws Exception {
        Objects.requireNonNull(logicalDate, "Frozen stk_limit logical date required");
        if (requestedThrough != null && requestedThrough.isAfter(logicalDate))
            throw new IllegalArgumentException("stk_limit --to exceeds logical date");
        var sourceNow = java.time.ZonedDateTime.now(DailySyncEndDate.ZONE);
        LocalDate completedSourceCeiling = DailySyncEndDate.resolve(null, sourceNow);
        LocalDate requestedLimit = requestedThrough == null ? logicalDate : requestedThrough;
        LocalDate resolvedThrough = DailySyncEndDate.resolve(requestedLimit, sourceNow);
        if (resolvedThrough.isAfter(logicalDate)) resolvedThrough = logicalDate;
        SyncJobDefinition definition = StockLimitSyncJobOwner.DEFINITION;
        Mode mode = requestedMode == null ? definition.defaultMode() : requestedMode;
        if (!definition.supportedModes().contains(mode)) throw new IllegalArgumentException("Unsupported stk_limit mode");
        requireExecutionMode(mode);
        if ((mode == Mode.BACKFILL || mode == Mode.RECONCILE) && requestedThrough == null)
            throw new IllegalArgumentException("Bounded stk_limit backfill/reconcile requires explicit --to");
        if (mode == Mode.INCREMENTAL && bootstrapFrom != null
                && (bootstrapFrom.isAfter(resolvedThrough)
                || java.time.temporal.ChronoUnit.DAYS.between(bootstrapFrom, resolvedThrough) >= definition.budget().maxWindowDays()))
            throw new IllegalArgumentException("Explicit stk_limit bootstrap exceeds the 366-day budget");
        if ((mode == Mode.BACKFILL || mode == Mode.RECONCILE)
                && (bootstrapFrom == null || bootstrapFrom.isAfter(resolvedThrough)))
            throw new IllegalArgumentException("Bounded stk_limit backfill/reconcile requires explicit --from and --to");
        if (bootstrapFrom != null && bootstrapFrom.isAfter(resolvedThrough))
            throw new IllegalArgumentException("stk_limit --from is after --to");

        String target = targetId();
        var port = this.target.newWriter(target);
        port.preflight();
        StockTargetRange physical = readTargetRange();
        if (physical.max() != null && physical.max().isAfter(logicalDate))
            throw new IllegalStateException("stk_limit target contains a date after frozen logical date");

        LocalDate from = bootstrapFrom, to = resolvedThrough, anchor = null, checkpointBefore = null;
        boolean bootstrap = false;
        Optional<StockLimitCoverage.Coverage> saved = Optional.empty();
        if (mode == Mode.INCREMENTAL) {
            saved = StockLimitCoverage.checkpoint(ledgerPath, target, tradingDates);
            if (saved.isEmpty()) {
                if (bootstrapFrom == null)
                    throw new IllegalArgumentException("Explicit bounded bootstrap --from required without a verified stk_limit checkpoint");
                if (physical.min() != null)
                    throw new IllegalStateException("Nonempty stk_limit target has no same-target incremental checkpoint; reconcile/inspect it first");
                from = bootstrapFrom; anchor = bootstrapFrom; bootstrap = true;
            } else {
                if (bootstrapFrom != null)
                    throw new IllegalArgumentException("--from is bootstrap-only; use BACKFILL for another explicit range");
                var coverage = saved.get(); checkpointBefore = coverage.through(); anchor = coverage.anchor();
                if (resolvedThrough.isBefore(checkpointBefore))
                    throw new IllegalArgumentException("stk_limit end precedes its verified checkpoint");
                from = checkpointBefore.minusDays(definition.revisionDays());
                if (from.isBefore(anchor)) from = anchor;
                if (physical.max() != null && physical.max().isAfter(completedSourceCeiling))
                    throw new IllegalStateException("stk_limit physical target extends beyond the completed-source ceiling; inspect partial-day rows before incremental catch-up");
                to = physical.max() != null && physical.max().isAfter(resolvedThrough) ? physical.max() : resolvedThrough;
                if (to.isAfter(logicalDate)) throw new IllegalStateException("Existing stk_limit target exceeds logical date");
                if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > definition.budget().maxWindowDays())
                    throw new IllegalArgumentException("Resolved stk_limit revision/catch-up window exceeds 366 days");
            }
            StockLimitCoverage.validateExistingTarget(ledgerPath, saved.orElse(null), target, tradingDates, port);
        } else if (mode == Mode.BACKFILL || mode == Mode.RECONCILE) {
            from = bootstrapFrom;
            if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > definition.budget().maxWindowDays())
                throw new IllegalArgumentException("stk_limit backfill/reconcile exceeds 366-day budget");
        } else throw new IllegalArgumentException("stk_limit supports only bounded incremental, backfill and reconcile");

        var sessions = tradingDates.read(from, to);
        var params = new LinkedHashMap<String,Object>();
        params.put("targetId", target); params.put("trade_dates", StockLimitSyncAdapter.encodeTradeDates(sessions));
        if (mode == Mode.INCREMENTAL) {
            params.put("checkpointAnchor", anchor);
            if (checkpointBefore != null) params.put("checkpointBefore", checkpointBefore);
        }
        if (physical.min() != null) params.put("targetMinBefore", physical.min());
        if (physical.max() != null) params.put("targetMaxBefore", physical.max());
        var request = jobs.prepare(definition.jobId(), definition.version(), mode, params, from, to, logicalDate);
        new StockLimitSyncAdapter(new StockLimitSource(pages, new com.zoutrankil.data.stock.mapper.StockLimitMapper(),
                ledgerPath.getParent().resolve("sync-evidence").resolve("stk-limit-preflight")),
                tradingDates, port, ledgerPath.getParent()).preflight(request);
        if (!target.equals(targetId())) throw new IllegalStateException("stk_limit target identity changed while planning");
        return new Plan(request, target, checkpointBefore, anchor, physical.min(), physical.max(), bootstrap);
    }

    private StockTargetRange readTargetRange() { return target.range(); }

    public SyncJobRunner.Result run(Plan plan) throws Exception {
        return execute("stk-limit-" + UUID.randomUUID(), null, plan);
    }
    public SyncJobRunner.Result resume(String priorRunId) throws Exception {
        var saved=FrozenRunRequest.restore(ledgerPath,priorRunId,StockLimitSyncJobOwner.DEFINITION);
        var parameters=saved.request().parameters();
        var plan=new Plan(saved.request(),saved.targetId(),(LocalDate)parameters.get("checkpointBefore"),
                (LocalDate)parameters.get("checkpointAnchor"),(LocalDate)parameters.get("targetMinBefore"),
                (LocalDate)parameters.get("targetMaxBefore"),!parameters.containsKey("checkpointBefore"));
        return resume(plan,priorRunId);
    }

    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        return execute("stk-limit-" + UUID.randomUUID(), Objects.requireNonNull(priorRunId), plan);
    }
    private SyncJobRunner.Result execute(String runId, String priorRunId, Plan plan) throws Exception {
        requireExecutionMode(plan.request().mode());
        if (!plan.request().definition().equals(StockLimitSyncJobOwner.DEFINITION)
                || !plan.targetId().equals(plan.request().parameters().get("targetId"))
                || !plan.targetId().equals(targetId()))
            throw new IllegalStateException("Frozen stk_limit plan or target identity changed before run");
        var ledger = new SyncRunLedger(ledgerPath);
        var port = target.newWriter(plan.targetId());
        var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        var adapter = new StockLimitSyncAdapter(new StockLimitSource(pages,
                new com.zoutrankil.data.stock.mapper.StockLimitMapper(), evidence.resolve("source")),
                tradingDates, port, evidence);
        var runner = new SyncJobRunner<StockLimit,StockLimitKey>(ledger, new DatasetIntervalLock(ledgerPath));
        java.util.function.BooleanSupplier cancelled = () -> {
            if (Thread.currentThread().isInterrupted()) return true;
            try { return ledger.cancellationRequested(runId); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot read stk_limit cancellation state", failure); }
        };
        return priorRunId == null ? runner.run(runId, null, plan.targetId(), plan.request(), adapter, cancelled)
                : runner.resume(runId, priorRunId, priorRunId, plan.targetId(), plan.request(), adapter, cancelled);
    }
}
