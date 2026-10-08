package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.etf.domain.EtfTargetRange;

import com.zoutrankil.data.etf.mapper.EtfDailyMapper;

import com.zoutrankil.data.service.DailySyncEndDate;
import com.zoutrankil.data.service.FrozenRunRequest;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.SyncRunExecution;
import com.zoutrankil.data.service.TusharePageService;

import com.zoutrankil.data.domain.policy.IsolatedTablePolicy;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Explicit bounded plan/run/resume/status owner for D014's externally owned etf_daily table. */
@Service
public final class EtfDailyJobService {
    public static final String ISOLATED_TABLE_PREFIX = IsolatedTablePolicy.ETF_DAILY.prefix();
    public record Plan(SyncJobDefinition.FrozenRequest request, String targetId, LocalDate checkpointBefore,
                       LocalDate checkpointAnchor, LocalDate targetMinDate, LocalDate targetMaxDate,
                       boolean bootstrap) {
        public Plan {
            Objects.requireNonNull(request); Objects.requireNonNull(targetId);
            if (!targetId.equals(request.parameters().get("targetId")))
                throw new IllegalArgumentException("etf_daily target identity must be frozen in the run request");
        }
    }
    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final EtfDailyTradingDates tradingDates;
    private final EtfTarget<EtfDaily, EtfDailyKey> target;
    private final Path ledgerPath;
    private final String table;

    @Autowired
    public EtfDailyJobService(@Lazy SyncJobRegistry jobs, TusharePageService pages,
            ExchangeCalendarReadRepository calendars, EtfTarget<EtfDaily, EtfDailyKey> target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(jobs, pages, calendars, target, Path.of(ledgerPath));
    }

    public EtfDailyJobService(SyncJobRegistry jobs, TusharePageService pages,
            ExchangeCalendarReadRepository calendars, EtfTarget<EtfDaily, EtfDailyKey> target,
            Path ledgerPath) {
        this.jobs = Objects.requireNonNull(jobs); this.pages = Objects.requireNonNull(pages);
        this.tradingDates = new EtfDailyTradingDates(calendars); this.target = Objects.requireNonNull(target);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
        String table = target.tableName(); requireExecutionTableName(table); this.table = table;
    }

    public String tableName() { return table; }

    public static void requireIsolatedTableName(String table) {
        IsolatedTablePolicy.ETF_DAILY.require(table);
    }

    /** Existing formal table is admitted explicitly; isolated creation remains a separate contract. */
    public static void requireExecutionTableName(String table) {
        EtfDailyDataset.requireExecutionTable(table);
    }

    public String targetId() throws Exception {
        requireExecutionTableName(table);
        return target.targetId();
    }

    /** Builds a frozen daily plan. Incremental uses receipt-backed coverage and a five-day revision overlap. */
    public Plan plan(Mode requestedMode, LocalDate bootstrapFrom, LocalDate requestedThrough, LocalDate logicalDate) throws Exception {
        if ("etf_daily".equals(table) && requestedMode != Mode.BACKFILL && requestedMode != Mode.RECONCILE)
            throw new IllegalArgumentException("Formal etf_daily refresh requires explicit bounded BACKFILL or RECONCILE");
        Objects.requireNonNull(logicalDate, "Frozen etf_daily logical date required");
        if (requestedThrough != null && requestedThrough.isAfter(logicalDate))
            throw new IllegalArgumentException("etf_daily --to exceeds logical date");
        var sourceNow = java.time.ZonedDateTime.now(DailySyncEndDate.ZONE);
        LocalDate completedSourceCeiling = DailySyncEndDate.resolve(null, sourceNow);
        LocalDate requestedLimit = requestedThrough == null ? logicalDate : requestedThrough;
        LocalDate resolvedThrough = DailySyncEndDate.resolve(requestedLimit, sourceNow);
        if (resolvedThrough.isAfter(logicalDate)) resolvedThrough = logicalDate;
        SyncJobDefinition definition = EtfDailySyncJobOwner.DEFINITION;
        Mode mode = requestedMode == null ? definition.defaultMode() : requestedMode;
        if (!definition.supportedModes().contains(mode)) throw new IllegalArgumentException("Unsupported etf_daily mode");
        if ((mode == Mode.BACKFILL || mode == Mode.RECONCILE) && requestedThrough == null)
            throw new IllegalArgumentException("Bounded etf_daily backfill/reconcile requires explicit --to");
        if (mode == Mode.INCREMENTAL && bootstrapFrom != null
                && (bootstrapFrom.isAfter(resolvedThrough)
                || java.time.temporal.ChronoUnit.DAYS.between(bootstrapFrom, resolvedThrough) >= definition.budget().maxWindowDays()))
            throw new IllegalArgumentException("Explicit etf_daily bootstrap exceeds the 366-day budget");
        if ((mode == Mode.BACKFILL || mode == Mode.RECONCILE)
                && (bootstrapFrom == null || bootstrapFrom.isAfter(resolvedThrough)))
            throw new IllegalArgumentException("Bounded etf_daily backfill/reconcile requires explicit --from and --to");
        if (bootstrapFrom != null && bootstrapFrom.isAfter(resolvedThrough))
            throw new IllegalArgumentException("etf_daily --from is after --to");

        String target = targetId();
        var port = this.target.newWriter(target);
        port.preflight();
        EtfTargetRange physical = readTargetRange();
        if (physical.max() != null && physical.max().isAfter(logicalDate))
            throw new IllegalStateException("etf_daily target contains a date after frozen logical date");

        LocalDate from = bootstrapFrom, to = resolvedThrough, anchor = null, checkpointBefore = null;
        boolean bootstrap = false;
        Optional<EtfDailyCoverage.Coverage> saved = Optional.empty();
        if (mode == Mode.INCREMENTAL) {
            saved = EtfDailyCoverage.checkpoint(ledgerPath, target, tradingDates);
            if (saved.isEmpty()) {
                if (bootstrapFrom == null)
                    throw new IllegalArgumentException("Explicit bounded bootstrap --from required without a verified etf_daily checkpoint");
                if (physical.min() != null)
                    throw new IllegalStateException("Nonempty etf_daily target has no same-target incremental checkpoint; reconcile/inspect it first");
                from = bootstrapFrom; anchor = bootstrapFrom; bootstrap = true;
            } else {
                if (bootstrapFrom != null)
                    throw new IllegalArgumentException("--from is bootstrap-only; use BACKFILL for another explicit range");
                var coverage = saved.get(); checkpointBefore = coverage.through(); anchor = coverage.anchor();
                if (resolvedThrough.isBefore(checkpointBefore))
                    throw new IllegalArgumentException("etf_daily end precedes its verified checkpoint");
                from = checkpointBefore.minusDays(definition.revisionDays());
                if (from.isBefore(anchor)) from = anchor;
                if (physical.max() != null && physical.max().isAfter(completedSourceCeiling))
                    throw new IllegalStateException("etf_daily physical target extends beyond the completed-source ceiling; inspect partial-day rows before incremental catch-up");
                to = physical.max() != null && physical.max().isAfter(resolvedThrough) ? physical.max() : resolvedThrough;
                if (to.isAfter(logicalDate)) throw new IllegalStateException("Existing etf_daily target exceeds logical date");
                if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > definition.budget().maxWindowDays())
                    throw new IllegalArgumentException("Resolved etf_daily revision/catch-up window exceeds 366 days");
            }
            EtfDailyCoverage.validateExistingTarget(ledgerPath, saved.orElse(null), target, tradingDates, port);
        } else if (mode == Mode.BACKFILL || mode == Mode.RECONCILE) {
            from = bootstrapFrom;
            if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > definition.budget().maxWindowDays())
                throw new IllegalArgumentException("etf_daily backfill/reconcile exceeds 366-day budget");
        } else throw new IllegalArgumentException("etf_daily supports only bounded incremental, backfill and reconcile");

        var sessions = tradingDates.read(from, to);
        var params = new LinkedHashMap<String,Object>();
        params.put("targetId", target); params.put("trade_dates", EtfDailySyncAdapter.encodeTradeDates(sessions));
        if (mode == Mode.INCREMENTAL) {
            params.put("checkpointAnchor", anchor);
            if (checkpointBefore != null) params.put("checkpointBefore", checkpointBefore);
        }
        if (physical.min() != null) params.put("targetMinBefore", physical.min());
        if (physical.max() != null) params.put("targetMaxBefore", physical.max());
        var request = jobs.prepare(definition.jobId(), definition.version(), mode, params, from, to, logicalDate);
        new EtfDailySyncAdapter(new EtfDailySource(pages, new EtfDailyMapper(),
                ledgerPath.getParent().resolve("sync-evidence").resolve("etf-daily-preflight")),
                tradingDates, port, ledgerPath.getParent()).preflight(request);
        if (!target.equals(targetId())) throw new IllegalStateException("etf_daily target identity changed while planning");
        return new Plan(request, target, checkpointBefore, anchor, physical.min(), physical.max(), bootstrap);
    }

    private EtfTargetRange readTargetRange() { return target.range(); }

    public SyncJobRunner.Result run(Plan plan) throws Exception {
        return execute("etf-daily-" + UUID.randomUUID(), null, plan);
    }
    public SyncJobRunner.Result resume(String priorRunId) throws Exception {
        var saved=FrozenRunRequest.restore(ledgerPath,priorRunId,EtfDailySyncJobOwner.DEFINITION);
        var parameters=saved.request().parameters();
        var plan=new Plan(saved.request(),saved.targetId(),(LocalDate)parameters.get("checkpointBefore"),
                (LocalDate)parameters.get("checkpointAnchor"),(LocalDate)parameters.get("targetMinBefore"),
                (LocalDate)parameters.get("targetMaxBefore"),!parameters.containsKey("checkpointBefore"));
        return resume(plan,priorRunId);
    }

    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        return execute("etf-daily-" + UUID.randomUUID(), Objects.requireNonNull(priorRunId), plan);
    }
    private SyncJobRunner.Result execute(String runId, String priorRunId, Plan plan) throws Exception {
        if ("etf_daily".equals(table) && plan.request().mode() != Mode.BACKFILL && plan.request().mode() != Mode.RECONCILE)
            throw new IllegalArgumentException("Formal etf_daily refresh requires bounded BACKFILL or RECONCILE");
        if (!plan.request().definition().equals(EtfDailySyncJobOwner.DEFINITION)
                || !plan.targetId().equals(plan.request().parameters().get("targetId"))
                || !plan.targetId().equals(targetId()))
            throw new IllegalStateException("Frozen etf_daily plan or target identity changed before run");
        return SyncRunExecution.execute(ledgerPath, runId, priorRunId, plan.targetId(), plan.request(),
                "Cannot read etf_daily cancellation state", () -> {
                    var port = target.newWriter(plan.targetId());
                    var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
                    return new EtfDailySyncAdapter(new EtfDailySource(pages,
                            new EtfDailyMapper(), evidence.resolve("source")), tradingDates, port, evidence);
                });
    }
}
