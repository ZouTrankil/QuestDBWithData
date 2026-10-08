package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.etf.domain.EtfTargetRange;

import com.zoutrankil.data.etf.mapper.EtfPortfolioMapper;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.SyncRequestIdentity;

import com.zoutrankil.data.domain.EtfPortfolio;
import com.zoutrankil.data.domain.EtfPortfolioDataset;
import com.zoutrankil.data.domain.EtfPortfolioKey;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** Explicit bounded plan/run/resume/status/cancel owner for the isolated D018 etf_portfolio target. */
@Service
public final class EtfPortfolioJobService {
    public record Plan(SyncJobDefinition.FrozenRequest request, String targetId,
                       LocalDate checkpointBefore, LocalDate checkpointAnchor,
                       LocalDate targetMinDate, LocalDate targetMaxDate, boolean bootstrap) {
        public Plan {
            Objects.requireNonNull(request); Objects.requireNonNull(targetId);
            if (!targetId.equals(request.parameters().get("targetId")))
                throw new IllegalArgumentException("etf_portfolio target identity must be frozen in request");
            if (!Objects.equals(targetMinDate, request.parameters().get("targetMinBefore"))
                    || !Objects.equals(targetMaxDate, request.parameters().get("targetMaxBefore")))
                throw new IllegalArgumentException("etf_portfolio physical range must match frozen request");
        }
    }

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime SOURCE_READY_AT = LocalTime.of(18, 0);
    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final EtfTarget<EtfPortfolio, EtfPortfolioKey> target;
    private final Path ledgerPath;
    private final String table;

    @Autowired
    public EtfPortfolioJobService(@Lazy SyncJobRegistry jobs, TusharePageService pages,
            EtfTarget<EtfPortfolio, EtfPortfolioKey> target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(jobs, pages, target, Path.of(ledgerPath));
    }

    public EtfPortfolioJobService(SyncJobRegistry jobs, TusharePageService pages,
            EtfTarget<EtfPortfolio, EtfPortfolioKey> target, Path ledgerPath) {
        this.jobs = Objects.requireNonNull(jobs); this.pages = Objects.requireNonNull(pages);
        this.target = Objects.requireNonNull(target);
        this.ledgerPath = Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();
        String table = target.tableName(); requireExecutionTableName(table); this.table = table;
    }

    public String tableName() { return table; }

    public static void requireIsolatedTableName(String value) { EtfPortfolioDataset.requireIsolatedTable(value); }

    public static void requireExecutionTableName(String value) {
        EtfPortfolioDataset.requireExecutionTable(value);
    }

    public String targetId() {
        requireExecutionTableName(table);
        return target.targetId();
    }

    /** Builds a finite announcement-date plan; bootstrap requires an explicit lower bound. */
    public Plan plan(Mode requestedMode, LocalDate bootstrapFrom, LocalDate requestedThrough, LocalDate logicalDate) throws Exception {
        Objects.requireNonNull(logicalDate, "Frozen etf_portfolio logical date required");
        if (requestedThrough != null && requestedThrough.isAfter(logicalDate))
            throw new IllegalArgumentException("etf_portfolio --to exceeds logical date");
        LocalDate ceiling = sourceCompletedThrough(logicalDate, requestedThrough);
        if (ceiling == null) throw new IllegalArgumentException("No completed etf_portfolio announcement date is available yet");
        Mode mode = requestedMode == null ? EtfPortfolioSyncJobOwner.DEFINITION.defaultMode() : requestedMode;
        if ("etf_portfolio".equals(table) && mode != Mode.BACKFILL)
            throw new IllegalArgumentException("Formal etf_portfolio refresh requires explicit bounded BACKFILL");
        if (!EtfPortfolioSyncJobOwner.DEFINITION.supportedModes().contains(mode))
            throw new IllegalArgumentException("Unsupported etf_portfolio sync mode");
        if (mode == Mode.BACKFILL && (bootstrapFrom == null || requestedThrough == null))
            throw new IllegalArgumentException("Bounded etf_portfolio backfill requires explicit --from and --to");

        String target = targetId(); var writer = this.target.newWriter(target);
        writer.preflight(); EtfTargetRange physical = readTargetRange();
        if (physical.max() != null && physical.max().isAfter(logicalDate))
            throw new IllegalStateException("etf_portfolio target exceeds frozen logical date");
        LocalDate from, to = ceiling, anchor = null, checkpointBefore = null;
        boolean bootstrap = false;
        EtfPortfolioCoverage.Coverage coverage = null;
        if (mode == Mode.INCREMENTAL) {
            var saved = EtfPortfolioCoverage.checkpoint(ledgerPath, target);
            coverage = saved.orElse(null);
            if (coverage == null) {
                if (bootstrapFrom == null)
                    throw new IllegalArgumentException("Explicit bounded etf_portfolio bootstrap --from required without a verified checkpoint");
                if (physical.min() != null)
                    throw new IllegalStateException("Nonempty etf_portfolio target lacks same-target verified coverage; reconcile/inspect first");
                from = bootstrapFrom; anchor = bootstrapFrom; bootstrap = true;
            } else {
                if (bootstrapFrom != null) throw new IllegalArgumentException("--from is bootstrap-only; use BACKFILL for another range");
                checkpointBefore = coverage.through(); anchor = coverage.anchor();
                if (to.isBefore(checkpointBefore)) throw new IllegalArgumentException("etf_portfolio --to precedes verified checkpoint");
                from = checkpointBefore.minusDays(EtfPortfolioSyncJobOwner.REVISION_DAYS);
                if (from.isBefore(anchor)) from = anchor;
                LocalDate maxEnd = from.plusDays(EtfPortfolioSyncJobOwner.MAX_WINDOW_DAYS - 1L);
                if (to.isAfter(maxEnd)) to = maxEnd; // A bounded catch-up advances in successive previews.
                if (physical.max() != null && physical.max().isAfter(sourceCompletedThrough(logicalDate, null)))
                    throw new IllegalStateException("etf_portfolio target extends beyond its completed-source ceiling");
            }
            EtfPortfolioCoverage.validateExistingTarget(ledgerPath, coverage, target, writer);
        } else {
            if (bootstrapFrom == null || bootstrapFrom.isAfter(to))
                throw new IllegalArgumentException("Increasing bounded etf_portfolio backfill interval required");
            from = bootstrapFrom;
        }
        if (from.isAfter(to)) throw new IllegalArgumentException("etf_portfolio --from is after resolved --to");
        if (ChronoUnit.DAYS.between(from, to) + 1 > EtfPortfolioSyncJobOwner.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("etf_portfolio request exceeds 45 calendar days");

        var dates = dates(from, to); var parameters = new LinkedHashMap<String, Object>();
        parameters.put("targetId", target); parameters.put("ann_dates", EtfPortfolioSyncAdapter.encodeAnnouncementDates(dates));
        parameters.put("observedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        if (mode == Mode.INCREMENTAL) {
            parameters.put("checkpointAnchor", anchor);
            if (checkpointBefore != null) parameters.put("checkpointBefore", checkpointBefore);
        }
        if (physical.min() != null) parameters.put("targetMinBefore", physical.min());
        if (physical.max() != null) parameters.put("targetMaxBefore", physical.max());
        var request = jobs.prepare(EtfPortfolioSyncJobOwner.DEFINITION.jobId(), EtfPortfolioSyncJobOwner.DEFINITION.version(),
                mode, parameters, from, to, logicalDate);
        new EtfPortfolioSyncAdapter(new EtfPortfolioSource(pages, new EtfPortfolioMapper(),
                ledgerPath.getParent().resolve("sync-evidence").resolve("etf-portfolio-preflight").resolve(UUID.randomUUID().toString())),
                writer, ledgerPath.getParent()).preflight(request);
        if (!target.equals(targetId())) throw new IllegalStateException("etf_portfolio target identity changed while planning");
        return new Plan(request, target, checkpointBefore, anchor, physical.min(), physical.max(), bootstrap);
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception { return execute("etf-portfolio-" + UUID.randomUUID(), null, plan); }

    /** Restores the exact immutable request and target from ledger for FAILED/CANCELLED/PARTIAL resume. */
    public SyncJobRunner.Result resume(String priorRunId) throws Exception {
        var saved = FrozenRunRequest.restore(ledgerPath, Objects.requireNonNull(priorRunId), EtfPortfolioSyncJobOwner.DEFINITION);
        var p = saved.request().parameters();
        var plan = new Plan(saved.request(), saved.targetId(), (LocalDate) p.get("checkpointBefore"),
                (LocalDate) p.get("checkpointAnchor"), (LocalDate) p.get("targetMinBefore"),
                (LocalDate) p.get("targetMaxBefore"), saved.request().mode() == Mode.INCREMENTAL
                        && !p.containsKey("checkpointBefore"));
        return resume(plan, priorRunId);
    }

    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        var saved = FrozenRunRequest.restore(ledgerPath, Objects.requireNonNull(priorRunId), EtfPortfolioSyncJobOwner.DEFINITION);
        if (!SyncRequestIdentity.fingerprint(saved.request(), saved.targetId()).equals(
                SyncRequestIdentity.fingerprint(plan.request(), plan.targetId())))
            throw new IllegalArgumentException("Resume requires the exact saved etf_portfolio request/target identity");
        return execute("etf-portfolio-" + UUID.randomUUID(), priorRunId, plan);
    }

    public SyncRunLedger.Entry status(String runId) throws Exception { return SyncRunLedger.openReadOnly(ledgerPath).get(Objects.requireNonNull(runId)); }
    public List<SyncRunLedger.Entry> entries(String runId, String afterId, int limit) throws Exception {
        return SyncRunLedger.openReadOnly(ledgerPath).entries(Objects.requireNonNull(runId), afterId, limit);
    }
    public boolean cancel(String runId) throws Exception { return new SyncRunLedger(ledgerPath).requestCancellation(Objects.requireNonNull(runId)); }

    private SyncJobRunner.Result execute(String runId, String priorRunId, Plan plan) throws Exception {
        Objects.requireNonNull(plan);
        if ("etf_portfolio".equals(table) && plan.request().mode() != Mode.BACKFILL)
            throw new IllegalArgumentException("Formal etf_portfolio refresh requires bounded BACKFILL");
        if (!plan.request().definition().equals(EtfPortfolioSyncJobOwner.DEFINITION)
                || !plan.targetId().equals(plan.request().parameters().get("targetId"))
                || !plan.targetId().equals(targetId()))
            throw new IllegalStateException("Frozen etf_portfolio plan/physical target changed before run");
        if (priorRunId == null) validateCurrentBaseline(plan);
        return SyncRunExecution.execute(ledgerPath, runId, priorRunId, plan.targetId(), plan.request(),
                "Cannot read etf_portfolio cancellation state", () -> {
                    var port = target.newWriter(plan.targetId());
                    var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
                    return new EtfPortfolioSyncAdapter(new EtfPortfolioSource(pages,
                            new EtfPortfolioMapper(), evidence.resolve("source")), port, evidence);
                });
    }

    private void validateCurrentBaseline(Plan plan) throws Exception {
        EtfTargetRange current = readTargetRange();
        if (!Objects.equals(current.min(), plan.targetMinDate()) || !Objects.equals(current.max(), plan.targetMaxDate()))
            throw new IllegalStateException("etf_portfolio physical range changed after plan; create a fresh plan");
        if (plan.request().mode() != Mode.INCREMENTAL) return;
        var saved = EtfPortfolioCoverage.checkpoint(ledgerPath, plan.targetId());
        if (saved.isPresent() != (plan.checkpointBefore() != null)
                || saved.isPresent() && (!saved.get().through().equals(plan.checkpointBefore())
                || !saved.get().anchor().equals(plan.checkpointAnchor())))
            throw new IllegalStateException("etf_portfolio verified checkpoint changed after plan");
        EtfPortfolioCoverage.validateExistingTarget(ledgerPath, saved.orElse(null), plan.targetId(),
                target.newWriter(plan.targetId()));
    }

    private EtfTargetRange readTargetRange() { return target.range(); }

    private static LocalDate sourceCompletedThrough(LocalDate logicalDate, LocalDate requested) {
        var now = java.time.ZonedDateTime.now(ZONE);
        LocalDate readyCeiling = now.toLocalTime().isBefore(SOURCE_READY_AT) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        if (readyCeiling.isAfter(logicalDate)) readyCeiling = logicalDate;
        return requested == null || requested.isAfter(readyCeiling) ? readyCeiling : requested;
    }
    private static List<LocalDate> dates(LocalDate from, LocalDate to) {
        int count = Math.toIntExact(ChronoUnit.DAYS.between(from, to) + 1);
        if (count < 1 || count > EtfPortfolioSyncJobOwner.MAX_WINDOW_DAYS) throw new IllegalArgumentException("etf_portfolio date window out of bounds");
        var dates = new java.util.ArrayList<LocalDate>(count);
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) dates.add(date);
        return List.copyOf(dates);
    }
}
