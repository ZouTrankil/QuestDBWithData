package com.zoutrankil.data.flow.application;

import com.zoutrankil.data.flow.domain.MoneyflowThsTargetRange;

import com.zoutrankil.data.flow.port.MoneyflowThsTarget;


import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.MoneyflowThsDataset;
import com.zoutrankil.data.domain.MoneyflowThs;
import com.zoutrankil.data.domain.MoneyflowThsKey;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.flow.port.MoneyflowThsWriteSession;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** D025 isolated manual planner/runner. Shared CLI and dataset/group wiring are registered separately. */
@Service
public class MoneyflowThsJobService {
    public static final String ISOLATED_TABLE_PREFIX = MoneyflowThsDataset.ISOLATED_PREFIX;
    public record Plan(SyncJobDefinition.FrozenRequest request, String targetId,
            LocalDate checkpointBefore, LocalDate checkpointAnchor,
            MoneyflowThsTargetRange physicalRange, boolean bootstrap, int checkedPhysicalRows) {
        public Plan {
            Objects.requireNonNull(request); Objects.requireNonNull(targetId); Objects.requireNonNull(physicalRange);
            if (!targetId.equals(request.parameters().get("targetId")) || checkedPhysicalRows < 0)
                throw new IllegalArgumentException("D025 plan must freeze target identity and nonnegative checks");
        }
    }

    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final ExchangeCalendarReadPort calendars;
    private final MoneyflowThsTarget target;
    private final Path ledgerPath;
    private final String table;

    @Autowired
    public MoneyflowThsJobService(@Lazy SyncJobRegistry jobs,TusharePageService pages,
            ExchangeCalendarReadPort calendars,MoneyflowThsTarget target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(jobs,pages,calendars,target,Path.of(ledgerPath));
    }
    public MoneyflowThsJobService(SyncJobRegistry jobs,TusharePageService pages,
            ExchangeCalendarReadPort calendars,MoneyflowThsTarget target,Path ledgerPath) {
        this.jobs=Objects.requireNonNull(jobs);this.pages=Objects.requireNonNull(pages);
        this.calendars=Objects.requireNonNull(calendars);this.target=Objects.requireNonNull(target);
        this.ledgerPath=ledgerPath.toAbsolutePath().normalize();
        String table=target.tableName();MoneyflowThsDataset.requireIsolatedTable(table);this.table=table;
    }

    public String tableName() { return table; }

    public static void requireIsolatedTableName(String value) { MoneyflowThsDataset.requireIsolatedTable(value); }

    /** First incremental bootstrap requires an explicit bounded start. */
    public Plan plan(Mode requestedMode, LocalDate bootstrapFrom, LocalDate requestedTo, LocalDate logicalDate)
            throws Exception {
        Objects.requireNonNull(logicalDate, "Frozen D025 logical date required");
        if (requestedTo != null && requestedTo.isAfter(logicalDate))
            throw new IllegalArgumentException("D025 --to exceeds the frozen logical date");
        Mode mode = requestedMode == null ? MoneyflowThsSyncJobOwner.DEFINITION.defaultMode() : requestedMode;
        if (!MoneyflowThsSyncJobOwner.DEFINITION.supportedModes().contains(mode))
            throw new IllegalArgumentException("Unsupported D025 synchronization mode");
        if ((mode == Mode.BACKFILL || mode == Mode.RECONCILE) && (bootstrapFrom == null || requestedTo == null))
            throw new IllegalArgumentException("D025 BACKFILL/RECONCILE require explicit --from and --to bounds");

        LocalDate sourceCeiling = DailySyncEndDate.resolve(null, ZonedDateTime.now(DailySyncEndDate.ZONE));
        LocalDate to = DailySyncEndDate.resolve(requestedTo, ZonedDateTime.now(DailySyncEndDate.ZONE));
        if (to.isAfter(logicalDate)) to = logicalDate;
        if (to.isAfter(sourceCeiling)) to = sourceCeiling;

        String target = targetId();
        var port = this.target.newWriter(target); port.preflight();
        var physical = port.readTargetRange();
        if (!physical.empty() && (physical.max().isAfter(logicalDate) || physical.max().isAfter(sourceCeiling)))
            throw new IllegalStateException("D025 target contains data beyond the frozen completed-source ceiling");
        MoneyflowThsCoverage.Checkpoint checkpoint = MoneyflowThsCoverage.checkpoint(ledgerPath, target, calendars);
        int checkedRows = MoneyflowThsCoverage.validateExistingTarget(ledgerPath, target, calendars, port);

        LocalDate from, anchor = null, checkpointBefore = null;
        boolean bootstrap = false;
        if (mode == Mode.INCREMENTAL) {
            if (checkpoint == null) {
                if (bootstrapFrom == null) throw new IllegalArgumentException("D025 first incremental run requires explicit bounded --from");
                from = bootstrapFrom;
                anchor = from; bootstrap = true;
            } else {
                if (bootstrapFrom != null)
                    throw new IllegalArgumentException("D025 --from is bootstrap-only after a verified checkpoint; use BACKFILL for another explicit range");
                if (to.isBefore(checkpoint.through()))
                    throw new IllegalArgumentException("D025 requested end precedes verified checkpoint; use bounded RECONCILE");
                checkpointBefore = checkpoint.through(); anchor = checkpoint.anchor();
                from = checkpointBefore.minusDays(MoneyflowThsSyncJobOwner.REVISION_DAYS - 1L);
                if (from.isBefore(anchor)) from = anchor;
            }
        } else {
            from = Objects.requireNonNull(bootstrapFrom);
        }

        if (from.isAfter(to)) throw new IllegalArgumentException("D025 source window is empty after completion ceiling");
        long span = ChronoUnit.DAYS.between(from, to) + 1;
        if (span < 1 || span > MoneyflowThsSyncJobOwner.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("D025 resolved window exceeds 366 calendar days");
        if (mode == Mode.INCREMENTAL && !physical.empty() && physical.max().isAfter(to))
            throw new IllegalStateException("D025 incremental end precedes actual target data; use a bounded correction run");
        var tradeDates = DailyTradingSessions.read(calendars, from, to);
        if (tradeDates.isEmpty()) throw new IllegalArgumentException("D025 window contains no verified SSE open date");

        var parameters = new java.util.LinkedHashMap<String, Object>(); parameters.put("targetId", target);
        if (mode == Mode.INCREMENTAL) {
            parameters.put("checkpointAnchor", anchor);
            if (checkpointBefore != null) parameters.put("checkpointBefore", checkpointBefore);
        }
        if (!physical.empty()) { parameters.put("targetMinBefore", physical.min()); parameters.put("targetMaxBefore", physical.max()); }
        var request = jobs.prepare(MoneyflowThsSyncJobOwner.DEFINITION.jobId(),
                MoneyflowThsSyncJobOwner.DEFINITION.version(), mode, parameters, from, to, logicalDate);
        var adapter = new MoneyflowThsSyncAdapter(new MoneyflowThsSource(pages,
                ledgerPath.getParent().resolve("sync-evidence").resolve("d025-preflight")), calendars, port,
                ledgerPath.getParent().resolve("d025-preflight"));
        adapter.preflight(request);
        if (!target.equals(targetId())) throw new IllegalStateException("D025 target identity changed during planning");
        return new Plan(request, target, checkpointBefore, anchor, physical, bootstrap, checkedRows);
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception {
        return execute("moneyflow-ths-" + UUID.randomUUID(), null, null, plan);
    }
    public SyncJobRunner.Result resume(String priorRunId) throws Exception {
        var restored = FrozenRunRequest.restore(ledgerPath, priorRunId, MoneyflowThsSyncJobOwner.DEFINITION);
        return resume(restorePlan(restored.request(), restored.targetId()), priorRunId);
    }
    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        return execute("moneyflow-ths-" + UUID.randomUUID(), null, Objects.requireNonNull(priorRunId), plan);
    }
    public SyncJobRunner.Result runAsGroupChild(String childRunId, String parentRunId, String expectedTarget,
            SyncJobDefinition.FrozenRequest request) throws Exception {
        if (!targetId().equals(expectedTarget)) throw new IllegalStateException("D025 write-group target identity changed");
        return execute(childRunId, parentRunId, null, restorePlan(request, expectedTarget));
    }

    private Plan restorePlan(SyncJobDefinition.FrozenRequest request, String target) {
        var params = request.parameters();
        return new Plan(request, target, (LocalDate) params.get("checkpointBefore"),
                (LocalDate) params.get("checkpointAnchor"),
                new MoneyflowThsTargetRange((LocalDate) params.get("targetMinBefore"),
                        (LocalDate) params.get("targetMaxBefore")), !params.containsKey("checkpointBefore"), 0);
    }

    private SyncJobRunner.Result execute(String runId, String parentRunId, String priorRunId, Plan plan) throws Exception {
        if (!plan.request().definition().equals(MoneyflowThsSyncJobOwner.DEFINITION)
                || !plan.targetId().equals(plan.request().parameters().get("targetId"))
                || !plan.targetId().equals(targetId()))
            throw new IllegalStateException("Frozen D025 plan/target identity changed before execution");
        var ledger = new SyncRunLedger(ledgerPath);
        var port = this.target.newWriter(plan.targetId());
        if (priorRunId == null) {
            port.preflight();
            if (!plan.physicalRange().equals(port.readTargetRange()))
                throw new IllegalStateException("D025 target range changed after plan; re-plan before starting writes");
        }
        Path evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        var adapter = new MoneyflowThsSyncAdapter(new MoneyflowThsSource(pages, evidence.resolve("source")), calendars, port, evidence);
        var runner = new SyncJobRunner<MoneyflowThs, MoneyflowThsKey>(ledger, new DatasetIntervalLock(ledgerPath));
        java.util.function.BooleanSupplier cancelled = () -> {
            if (Thread.currentThread().isInterrupted()) return true;
            try { return ledger.cancellationRequested(runId); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot read D025 cancellation state", failure); }
        };
        return priorRunId == null ? runner.run(runId, parentRunId, plan.targetId(), plan.request(), adapter, cancelled)
                : runner.resume(runId, parentRunId, priorRunId, plan.targetId(), plan.request(), adapter, cancelled);
    }

    public String targetId(){return target.targetId();}
}
