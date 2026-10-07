package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.etf.domain.EtfTargetRange;

import com.zoutrankil.data.service.DailySyncEndDate;
import com.zoutrankil.data.service.FrozenRunRequest;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.SyncRunExecution;
import com.zoutrankil.data.service.TusharePageService;

import com.zoutrankil.data.domain.EtfFactor;
import com.zoutrankil.data.domain.EtfFactorDataset;
import com.zoutrankil.data.domain.EtfFactorKey;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.etf.mapper.EtfFactorMapper;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.repository.SyncRunLedger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Manual bounded plan/run/recovery entry point for D017's isolated etf_factor target. */
@Service
public final class EtfFactorJobService {
    public static final String ISOLATED_TABLE_PREFIX = EtfFactorDataset.ISOLATED_PREFIX;
    public static final int MAX_ROWS_PER_DATE = EtfFactorDataset.SOURCE_ROW_CAP;

    public record Plan(SyncJobDefinition.FrozenRequest request, String targetId,
                       LocalDate checkpointBefore, LocalDate checkpointAnchor,
                       LocalDate targetMinDate, LocalDate targetMaxDate,
                       LocalDate requestedThrough, boolean bootstrap, boolean cappedByBudget) {
        public Plan {
            Objects.requireNonNull(request); Objects.requireNonNull(targetId);
            if (!targetId.equals(request.parameters().get("targetId")))
                throw new IllegalArgumentException("etf_factor target identity must be frozen in the request");
            if (!Objects.equals(checkpointBefore, request.parameters().get("checkpointBefore"))
                    || !Objects.equals(checkpointAnchor, request.parameters().get("checkpointAnchor"))
                    || !Objects.equals(targetMinDate, request.parameters().get("targetMinBefore"))
                    || !Objects.equals(targetMaxDate, request.parameters().get("targetMaxBefore")))
                throw new IllegalArgumentException("etf_factor plan metadata differs from its frozen request");
            if (request.mode() == Mode.INCREMENTAL && checkpointAnchor == null)
                throw new IllegalArgumentException("Incremental etf_factor plan requires a frozen coverage anchor");
        }
    }


    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final EtfFactorTradingDates tradingDates;
    private final EtfTarget<EtfFactor, EtfFactorKey> target;
    private final Path ledgerPath;
    private final String table;

    @Autowired
    public EtfFactorJobService(@Lazy SyncJobRegistry jobs, TusharePageService pages,
            ExchangeCalendarReadRepository calendars, EtfTarget<EtfFactor, EtfFactorKey> target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(jobs, pages, calendars, target, Path.of(ledgerPath));
    }

    public EtfFactorJobService(SyncJobRegistry jobs, TusharePageService pages,
            ExchangeCalendarReadRepository calendars, EtfTarget<EtfFactor, EtfFactorKey> target,
            Path ledgerPath) {
        this.jobs = Objects.requireNonNull(jobs); this.pages = Objects.requireNonNull(pages);
        this.tradingDates = new EtfFactorTradingDates(calendars); this.target = Objects.requireNonNull(target);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
        String table = target.tableName(); requireExecutionTableName(table); this.table = table;
    }

    public String tableName() { return table; }

    public static void requireIsolatedTableName(String table) {
        EtfFactorDataset.requireIsolatedTable(table);
    }

    public static void requireExecutionTableName(String table) {
        EtfFactorDataset.requireExecutionTable(table);
    }

    /** Returns the configured endpoint, table and physical table generation as a non-secret identity. */
    public String targetId() {
        requireExecutionTableName(table);
        return target.targetId();
    }

    /**
     * Freeze one bounded source interval. Incremental resumes only from a contiguous verified
     * receipt chain, overlaps five calendar days for revisions, and accounts for the actual
     * physical range before planning. First bootstrap is explicit and at most 366 calendar days.
     */
    public Plan planDetailed(Mode requestedMode, LocalDate bootstrapFrom,
                             LocalDate requestedThrough, LocalDate logicalDate) throws Exception {
        Objects.requireNonNull(logicalDate, "Frozen etf_factor logical date required");
        if (requestedThrough != null && requestedThrough.isAfter(logicalDate))
            throw new IllegalArgumentException("etf_factor --to exceeds frozen logical date");

        var now = ZonedDateTime.now(DailySyncEndDate.ZONE);
        LocalDate completedSourceCeiling = DailySyncEndDate.resolve(null, now);
        LocalDate resolvedThrough = DailySyncEndDate.resolve(requestedThrough, now);
        if (resolvedThrough.isAfter(logicalDate)) resolvedThrough = logicalDate;
        SyncJobDefinition definition = EtfFactorSyncJobOwner.DEFINITION;
        Mode mode = requestedMode == null ? definition.defaultMode() : requestedMode;
        if ("etf_factor".equals(table) && mode != Mode.BACKFILL && mode != Mode.RECONCILE)
            throw new IllegalArgumentException("Formal etf_factor refresh requires explicit bounded BACKFILL or RECONCILE");
        if (!definition.supportedModes().contains(mode)) throw new IllegalArgumentException("Unsupported etf_factor mode");
        if (mode != Mode.INCREMENTAL && (bootstrapFrom == null || requestedThrough == null))
            throw new IllegalArgumentException("Bounded etf_factor backfill/reconcile requires explicit --from and --to");
        if (bootstrapFrom != null && bootstrapFrom.isAfter(resolvedThrough))
            throw new IllegalArgumentException("etf_factor --from is after its completed --to");

        String frozenTarget = targetId();
        var port = target.newWriter(frozenTarget);
        port.preflight();
        EtfTargetRange physical = readTargetRange();
        requireSameTarget(frozenTarget, targetId());
        if (physical.max() != null && physical.max().isAfter(logicalDate))
            throw new IllegalStateException("etf_factor target contains a trade_date after the frozen logical date");

        LocalDate from = bootstrapFrom;
        LocalDate to = resolvedThrough;
        LocalDate checkpointBefore = null;
        LocalDate checkpointAnchor = null;
        boolean bootstrap = false;
        boolean cappedByBudget = false;
        Optional<EtfFactorCoverage.Coverage> checkpoint = Optional.empty();

        if (mode == Mode.INCREMENTAL) {
            checkpoint = EtfFactorCoverage.checkpoint(ledgerPath, frozenTarget, tradingDates);
            if (checkpoint.isEmpty()) {
                if (bootstrapFrom == null)
                    throw new IllegalArgumentException("Explicit bounded bootstrap --from required without a verified etf_factor checkpoint");
                if (physical.min() != null)
                    throw new IllegalStateException("Nonempty etf_factor target has no same-target receipt-backed checkpoint; reconcile it first");
                from = bootstrapFrom;
                checkpointAnchor = bootstrapFrom;
                bootstrap = true;
                LocalDate lastAllowed = from.plusDays(definition.budget().maxWindowDays() - 1L);
                if (to.isAfter(lastAllowed)) { to = lastAllowed; cappedByBudget = true; }
            } else {
                if (bootstrapFrom != null)
                    throw new IllegalArgumentException("--from is bootstrap-only; use bounded BACKFILL for another range");
                EtfFactorCoverage.Coverage saved = checkpoint.get();
                checkpointBefore = saved.through(); checkpointAnchor = saved.anchor();
                if (resolvedThrough.isBefore(checkpointBefore))
                    throw new IllegalArgumentException("etf_factor --to precedes the verified checkpoint");
                from = checkpointBefore.minusDays(definition.revisionDays());
                if (from.isBefore(checkpointAnchor)) from = checkpointAnchor;
                if (physical.max() != null && physical.max().isAfter(completedSourceCeiling))
                    throw new IllegalStateException("etf_factor target extends beyond completed-source ceiling; inspect partial-day rows first");
                LocalDate desiredThrough = physical.max() != null && physical.max().isAfter(resolvedThrough)
                        ? physical.max() : resolvedThrough;
                if (desiredThrough.isAfter(logicalDate) || desiredThrough.isAfter(completedSourceCeiling))
                    throw new IllegalStateException("etf_factor incremental ceiling exceeds completed source/logical date");
                LocalDate lastAllowed = from.plusDays(definition.budget().maxWindowDays() - 1L);
                to = desiredThrough.isAfter(lastAllowed) ? lastAllowed : desiredThrough;
                cappedByBudget = to.isBefore(desiredThrough);
                if (to.isBefore(checkpointBefore))
                    throw new IllegalStateException("Resolved etf_factor overlap window ends before its verified checkpoint");
            }
            EtfFactorCoverage.validateExistingTarget(checkpoint.orElse(null), tradingDates, port);
        } else {
            from = bootstrapFrom;
            if (to.isAfter(logicalDate)) throw new IllegalArgumentException("etf_factor bounded window exceeds logical date");
        }

        long span = ChronoUnit.DAYS.between(from, to) + 1;
        if (span < 1 || span > definition.budget().maxWindowDays())
            throw new IllegalArgumentException("Resolved etf_factor interval exceeds 366 calendar days");
        var dates = tradingDates.read(from, to);
        var parameters = new LinkedHashMap<String,Object>();
        parameters.put("targetId", frozenTarget);
        parameters.put("trade_dates", EtfFactorSyncAdapter.encodeDates(dates));
        if (mode == Mode.INCREMENTAL) {
            parameters.put("checkpointAnchor", checkpointAnchor);
            if (checkpointBefore != null) parameters.put("checkpointBefore", checkpointBefore);
        }
        if (physical.min() != null) parameters.put("targetMinBefore", physical.min());
        if (physical.max() != null) parameters.put("targetMaxBefore", physical.max());
        var request = jobs.prepare(definition.jobId(), definition.version(), mode, parameters, from, to, logicalDate);
        new EtfFactorSyncAdapter(new EtfFactorSource(pages, new EtfFactorMapper(),
                ledgerPath.getParent().resolve("sync-evidence").resolve("etf-factor-preflight-source")),
                tradingDates, port, ledgerPath.getParent().resolve("sync-evidence").resolve("etf-factor-preflight"))
                .preflight(request);
        requireSameTarget(frozenTarget, targetId());
        return new Plan(request, frozenTarget, checkpointBefore, checkpointAnchor,
                physical.min(), physical.max(), requestedThrough == null ? resolvedThrough : requestedThrough,
                bootstrap, cappedByBudget);
    }

    public Plan plan(Mode mode, LocalDate from, LocalDate to, LocalDate logicalDate) throws Exception {
        return planDetailed(mode, from, to, logicalDate);
    }

    /** Runs only the exact previewed request and rejects changes to its target/baseline first. */
    public SyncJobRunner.Result run(Plan plan) throws Exception {
        return execute("etf-factor-" + UUID.randomUUID(), null, Objects.requireNonNull(plan));
    }

    public SyncJobRunner.Result resume(String priorRunId) throws Exception {
        var restored = FrozenRunRequest.restore(ledgerPath, Objects.requireNonNull(priorRunId), EtfFactorSyncJobOwner.DEFINITION);
        return resume(planFromFrozen(restored.request(), restored.targetId()), priorRunId);
    }

    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        Objects.requireNonNull(plan); Objects.requireNonNull(priorRunId);
        var restored = FrozenRunRequest.restore(ledgerPath, priorRunId, EtfFactorSyncJobOwner.DEFINITION);
        if (!SyncRequestIdentity.fingerprint(restored.request(), restored.targetId()).equals(
                SyncRequestIdentity.fingerprint(plan.request(), plan.targetId())))
            throw new IllegalArgumentException("Resume requires the exact saved etf_factor frozen request and target");
        return execute("etf-factor-" + UUID.randomUUID(), priorRunId, plan);
    }

    public SyncRunLedger.Entry status(String runId) throws Exception {
        return SyncRunLedger.openReadOnly(ledgerPath).get(Objects.requireNonNull(runId));
    }

    public List<SyncRunLedger.Entry> entries(String runId, String afterId, int limit) throws Exception {
        return SyncRunLedger.openReadOnly(ledgerPath).entries(Objects.requireNonNull(runId), afterId, limit);
    }

    public boolean cancel(String runId) throws Exception {
        return new SyncRunLedger(ledgerPath).requestCancellation(Objects.requireNonNull(runId));
    }

    private SyncJobRunner.Result execute(String runId, String priorRunId, Plan plan) throws Exception {
        var request = plan.request();
        if ("etf_factor".equals(table) && request.mode() != Mode.BACKFILL && request.mode() != Mode.RECONCILE)
            throw new IllegalArgumentException("Formal etf_factor refresh requires bounded BACKFILL or RECONCILE");
        if (!request.definition().equals(EtfFactorSyncJobOwner.DEFINITION)
                || !plan.targetId().equals(request.parameters().get("targetId")))
            throw new IllegalArgumentException("Frozen etf_factor job definition or target identity differs");
        requireSameTarget(plan.targetId(), targetId());
        if (priorRunId == null) validateCurrentBaseline(plan);

        return SyncRunExecution.execute(ledgerPath, runId, priorRunId, plan.targetId(), request,
                "Cannot read etf_factor cancellation state", () -> {
                    var port = target.newWriter(plan.targetId());
                    var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
                    return new EtfFactorSyncAdapter(new EtfFactorSource(pages,
                            new EtfFactorMapper(), evidence.resolve("source")), tradingDates, port, evidence);
                });
    }

    private void validateCurrentBaseline(Plan plan) throws Exception {
        EtfTargetRange current = readTargetRange();
        if (!Objects.equals(current.min(), plan.targetMinDate()) || !Objects.equals(current.max(), plan.targetMaxDate()))
            throw new IllegalStateException("etf_factor target range changed after planning; build a fresh plan");
        requireSameTarget(plan.targetId(), targetId());
        if (plan.request().mode() != Mode.INCREMENTAL) return;

        var saved = EtfFactorCoverage.checkpoint(ledgerPath, plan.targetId(), tradingDates);
        if (saved.isPresent() != (plan.checkpointBefore() != null)
                || saved.isPresent() && (!saved.get().through().equals(plan.checkpointBefore())
                || !saved.get().anchor().equals(plan.checkpointAnchor())))
            throw new IllegalStateException("etf_factor verified checkpoint changed after planning; build a fresh plan");
        var port = target.newWriter(plan.targetId());
        EtfFactorCoverage.validateExistingTarget(saved.orElse(null), tradingDates, port);
    }

    private Plan planFromFrozen(SyncJobDefinition.FrozenRequest request, String targetId) {
        var p = request.parameters();
        return new Plan(request, targetId, (LocalDate)p.get("checkpointBefore"),
                (LocalDate)p.get("checkpointAnchor"), (LocalDate)p.get("targetMinBefore"),
                (LocalDate)p.get("targetMaxBefore"), request.to(), p.get("checkpointBefore") == null,
                false);
    }

    private EtfTargetRange readTargetRange() { return target.range(); }

    private static void requireSameTarget(String expected, String actual) {
        if (expected == null || !expected.equals(actual))
            throw new IllegalStateException("etf_factor physical target identity changed after planning");
    }
}
