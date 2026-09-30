package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.repository.*;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Explicit finite D012 planner/runner for the external ST table's isolated acceptance target. */
@Service
public final class StockStDailyJobService implements SyncJobOwner {
    public static final String ISOLATED_TABLE_PREFIX = "java_d012_stk_st_daily_";
    public record Plan(SyncJobDefinition.FrozenRequest request, String targetId, String physicalTargetId, LocalDate checkpointBefore,
                       LocalDate checkpointAnchor, LocalDate targetMinDate, LocalDate targetMaxDate,
                       boolean bootstrap) {
        public Plan {
            Objects.requireNonNull(request); Objects.requireNonNull(targetId); Objects.requireNonNull(physicalTargetId);
            if (!targetId.equals(request.parameters().get("targetId"))
                    || !physicalTargetId.equals(request.parameters().get("physicalTargetId")))
                throw new IllegalArgumentException("Frozen stk_st_daily logical/physical target identities differ");
        }
    }
    private record TargetRange(LocalDate min, LocalDate max) {
        TargetRange {
            if ((min == null) != (max == null) || min != null && min.isAfter(max))
                throw new IllegalStateException("Invalid stk_st_daily physical date range");
        }
    }

    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final StockStTradingDates tradingDates;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final Path ledgerPath;
    private final String table;

    @Autowired
    public StockStDailyJobService(@Lazy SyncJobRegistry jobs, TusharePageService pages,
            ExchangeCalendarReadRepository calendars, JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            @Value("${app.sync.stk-st-daily-table:java_d012_stk_st_daily_acceptance}") String table) {
        this(jobs, pages, calendars, jdbc, questdb, Path.of(ledgerPath), table);
    }
    public StockStDailyJobService(SyncJobRegistry jobs, TusharePageService pages,
            ExchangeCalendarReadRepository calendars, JdbcTemplate jdbc, QuestDB questdb,
            Path ledgerPath, String table) {
        this.jobs = Objects.requireNonNull(jobs); this.pages = Objects.requireNonNull(pages);
        this.tradingDates = new StockStTradingDates(calendars); this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb); this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
        requireIsolatedTableName(table); this.table = table;
    }

    @Override public String datasetId() { return "stk_st_daily"; }
    @Override public Set<Mode> supportedSyncModes() { return StockStDailySyncJobOwner.DEFINITION.supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(StockStDailySyncJobOwner.DEFINITION); }
    public String tableName() { return table; }

    public static void requireIsolatedTableName(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_TABLE_PREFIX) || table.length() <= ISOLATED_TABLE_PREFIX.length())
            throw new IllegalStateException("D012 execution requires a dedicated java_d012_stk_st_daily_<suffix> isolated target");
    }

    /** Stable endpoint/table identity used by the ledger across journaled table generations. */
    public String targetId() {
        requireIsolatedTableName(table);
        String physical = StaticTargetIdentity.identify(jdbc, table, 0L, "d012-logical-target-v1");
        return "d012-logical-v1-" + physical.substring("static-v2-".length());
    }

    /** Exact current QuestDB generation; frozen separately from the stable logical checkpoint identity. */
    public String physicalTargetId() {
        requireIsolatedTableName(table);
        var identity = new StockStDailyStorage(jdbc, table).preflight();
        return StockStDailyStorage.targetId(jdbc, table, identity);
    }

    /** First incremental use requires an explicit empty-target bootstrap; later runs re-fetch 30 days. */
    public Plan plan(Mode requestedMode, LocalDate bootstrapFrom, LocalDate requestedThrough,
            LocalDate logicalDate) throws Exception {
        Objects.requireNonNull(logicalDate, "Frozen stk_st_daily logical date required");
        requireNoPendingPublication();
        var definition = StockStDailySyncJobOwner.DEFINITION;
        Mode mode = requestedMode == null ? definition.defaultMode() : requestedMode;
        if (!definition.supportedModes().contains(mode)) throw new IllegalArgumentException("Unsupported stk_st_daily mode");
        if (requestedThrough == null && mode == Mode.BACKFILL)
            throw new IllegalArgumentException("Bounded stk_st_daily BACKFILL requires explicit --to");
        LocalDate resolvedThrough = requestedThrough == null ? logicalDate : requestedThrough;
        if (resolvedThrough.isAfter(logicalDate)) throw new IllegalArgumentException("stk_st_daily --to exceeds logical date");
        if (bootstrapFrom != null && bootstrapFrom.isAfter(resolvedThrough))
            throw new IllegalArgumentException("stk_st_daily --from is after --to");
        if ((mode == Mode.BACKFILL) && bootstrapFrom == null)
            throw new IllegalArgumentException("Bounded stk_st_daily BACKFILL requires explicit --from and --to");
        if (mode == Mode.INCREMENTAL && bootstrapFrom != null
                && ChronoUnit.DAYS.between(bootstrapFrom, resolvedThrough) + 1 > definition.budget().maxWindowDays())
            throw new IllegalArgumentException("Explicit stk_st_daily bootstrap exceeds its 366-day bound");
        if (mode == Mode.BACKFILL
                && ChronoUnit.DAYS.between(bootstrapFrom, resolvedThrough) + 1 > definition.budget().maxWindowDays())
            throw new IllegalArgumentException("stk_st_daily BACKFILL exceeds its 366-day bound");

        String target = targetId();
        String physicalTarget = physicalTargetId();
        var writer = new StockStDailyWritePort(table, physicalTarget, jdbc, questdb);
        writer.preflight();
        TargetRange physical = readTargetRange();
        if (physical.max() != null && physical.max().isAfter(logicalDate))
            throw new IllegalStateException("stk_st_daily target contains a date after frozen logicalDate");
        LocalDate from = bootstrapFrom, to = resolvedThrough, checkpointBefore = null, anchor = null;
        boolean bootstrap = false;
        Optional<StockStDailyCoverage.Coverage> saved = Optional.empty();
        if (mode == Mode.INCREMENTAL) {
            if (StockStDailyCoverage.hasHistorySchema(ledgerPath))
                saved = StockStDailyCoverage.checkpoint(ledgerPath, target, tradingDates);
            StockStDailyCoverage.validateExistingTarget(saved.orElse(null), tradingDates, writer);
            if (saved.isEmpty()) {
                if (bootstrapFrom == null)
                    throw new IllegalArgumentException("Explicit bounded stk_st_daily bootstrap --from required without verified checkpoint");
                if (physical.min() != null)
                    throw new IllegalStateException("Nonempty stk_st_daily target has no same-target incremental checkpoint");
                from = bootstrapFrom; anchor = bootstrapFrom; bootstrap = true;
            } else {
                if (bootstrapFrom != null)
                    throw new IllegalArgumentException("--from is bootstrap-only; use BACKFILL for another explicit interval");
                var coverage = saved.get(); checkpointBefore = coverage.through(); anchor = coverage.anchor();
                if (resolvedThrough.isBefore(checkpointBefore))
                    throw new IllegalArgumentException("stk_st_daily end precedes verified checkpoint");
                from = coverage.through().minusDays(StockStDailySyncJobOwner.REVISION_DAYS - 1L);
                if (from.isBefore(anchor)) from = anchor;
            }
        } else if (mode == Mode.BACKFILL) {
            from = bootstrapFrom;
            if (physical.min() != null) {
                if (saved.isEmpty()) {
                    if (StockStDailyCoverage.hasHistorySchema(ledgerPath))
                        saved = StockStDailyCoverage.checkpoint(ledgerPath, target, tradingDates);
                }
                StockStDailyCoverage.validateExistingTarget(saved.orElse(null), tradingDates, writer);
            }
        }
        long span = ChronoUnit.DAYS.between(from, to) + 1;
        if (span < 1 || span > definition.budget().maxWindowDays() || from.isBefore(StockStDailySource.HISTORY_ANCHOR))
            throw new IllegalArgumentException("Resolved stk_st_daily interval exceeds its 366-day/20100101 source bounds");
        List<LocalDate> sessions = tradingDates.read(from, to);
        var params = new LinkedHashMap<String,Object>();
        params.put("targetId", target); params.put("physicalTargetId", physicalTarget);
        params.put("trade_dates", StockStDailySyncAdapter.encodeTradeDates(sessions));
        if (mode == Mode.INCREMENTAL) {
            params.put("checkpointAnchor", anchor);
            if (checkpointBefore != null) params.put("checkpointBefore", checkpointBefore);
        }
        if (physical.min() != null) params.put("targetMinBefore", physical.min());
        if (physical.max() != null) params.put("targetMaxBefore", physical.max());
        var request = jobs.prepare(definition.jobId(), definition.version(), mode, params, from, to, logicalDate);
        var adapter = new StockStDailySyncAdapter(new StockStDailySource(pages, new com.zoutrankil.data.mapper.StockStDailyMapper(),
                ledgerPath.getParent().resolve("sync-evidence").resolve("stk-st-preflight")), tradingDates, writer, ledgerPath.getParent());
        adapter.preflight(request);
        if (!target.equals(targetId()) || !physicalTarget.equals(physicalTargetId()))
            throw new IllegalStateException("stk_st_daily target identity changed while planning");
        return new Plan(request, target, physicalTarget, checkpointBefore, anchor, physical.min(), physical.max(), bootstrap);
    }

    private TargetRange readTargetRange() {
        String sql = "SELECT cast(min(timestamp) AS long) AS min_micros, cast(max(timestamp) AS long) AS max_micros FROM \"" + table + "\"";
        return jdbc.query(sql, rs -> {
            if (!rs.next()) throw new IllegalStateException("QuestDB did not return stk_st_daily range aggregate");
            Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
            if (min == null && max == null) return new TargetRange(null, null);
            if (!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                throw new IllegalStateException("QuestDB stk_st_daily range has invalid timestamp types");
            var from = com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(minValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            var to = com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(maxValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            return new TargetRange(from, to);
        });
    }

    public void requireNoPendingPublication() throws Exception {
        if (java.nio.file.Files.isRegularFile(ledgerPath))
            new StockStDailyPublication(jdbc, ledgerPath).requireNoPendingPublication();
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception { return execute("stk-st-daily-" + UUID.randomUUID(), null, plan); }
    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        var prior = ledger.getRun(priorRunId); var priorState = ledger.get(priorRunId).state();
        if (!Set.of(SyncRunState.FAILED, SyncRunState.CANCELLED, SyncRunState.PARTIAL).contains(priorState)
                || !prior.jobId().equals(StockStDailySyncJobOwner.DEFINITION.jobId()) || prior.jobVersion() != 1
                || !SyncRequestIdentity.fingerprint(prior.frozenJson(), prior.targetId())
                        .equals(SyncRequestIdentity.fingerprint(plan.request(), plan.targetId()))
                || !prior.targetId().equals(plan.targetId())
                || new DatasetIntervalLock(ledgerPath).findOwned(priorRunId,
                        new DatasetIntervalLock.Scope("stk_st_daily", plan.request().from(), plan.request().to())) != null
                || new StockStDailyPublication(jdbc, ledgerPath).findForRun(priorRunId).isPresent())
            throw new IllegalStateException("D012 resume requires the exact failed/cancelled frozen request and no unresolved publication");
        return execute("stk-st-daily-" + UUID.randomUUID(), Objects.requireNonNull(priorRunId), plan);
    }

    /** Restore the exact prior frozen request; resume never replans against a newer calendar or physical generation. */
    public Plan restorePlan(String priorRunId) throws Exception {
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        var run = ledger.getRun(priorRunId); var state = ledger.get(priorRunId).state();
        if (!Set.of(SyncRunState.FAILED, SyncRunState.CANCELLED, SyncRunState.PARTIAL).contains(state)
                || !"data.stk_st_daily".equals(run.jobId()) || run.jobVersion() != 1)
            throw new IllegalStateException("Only a terminal failed/cancelled D012 request can be resumed");
        if (new StockStDailyPublication(jdbc, ledgerPath).findForRun(priorRunId).isPresent())
            throw new IllegalStateException("D012 publication intent requires finishInterrupted before resume");
        var restored = FrozenRunRequest.restore(ledgerPath, priorRunId, StockStDailySyncJobOwner.DEFINITION);
        var request = restored.request();
        StockStDailySyncAdapter.validateFrozenRequest(request);
        if (new DatasetIntervalLock(ledgerPath).findOwned(priorRunId,
                new DatasetIntervalLock.Scope("stk_st_daily", request.from(), request.to())) != null)
            throw new IllegalStateException("Retained D012 interval lease requires reconciliation before resuming");
        String logicalTarget = request.parameters().get("targetId").toString();
        String physicalTarget = request.parameters().get("physicalTargetId").toString();
        if (!logicalTarget.equals(run.targetId()) || !logicalTarget.equals(targetId())
                || !physicalTarget.equals(physicalTargetId()))
            throw new IllegalStateException("D012 exact frozen target generation changed; resume is unsafe");
        var writer = new StockStDailyWritePort(table, physicalTarget, jdbc, questdb);
        var adapter = new StockStDailySyncAdapter(new StockStDailySource(pages,
                new com.zoutrankil.data.mapper.StockStDailyMapper(),
                ledgerPath.getParent().resolve("sync-evidence").resolve("stk-st-resume-preflight")),
                tradingDates, writer, ledgerPath.getParent());
        adapter.preflight(request);
        var parameters = request.parameters();
        LocalDate anchor = parameters.get("checkpointAnchor") instanceof LocalDate date ? date : null;
        LocalDate before = parameters.get("checkpointBefore") instanceof LocalDate date ? date : null;
        LocalDate min = parameters.get("targetMinBefore") instanceof LocalDate date ? date : null;
        LocalDate max = parameters.get("targetMaxBefore") instanceof LocalDate date ? date : null;
        return new Plan(request, logicalTarget, physicalTarget, before, anchor, min, max,
                request.mode() == Mode.INCREMENTAL && before == null);
    }

    public StockStDailyRunRecovery.Result finishInterrupted(String runId, boolean writerStopped) throws Exception {
        return StockStDailyRunRecovery.finishInterrupted(jdbc, questdb, ledgerPath, table, runId, writerStopped);
    }
    private SyncJobRunner.Result execute(String runId, String priorRunId, Plan plan) throws Exception {
        if (!plan.request().definition().equals(StockStDailySyncJobOwner.DEFINITION)
                || !plan.targetId().equals(plan.request().parameters().get("targetId"))
                || !plan.physicalTargetId().equals(plan.request().parameters().get("physicalTargetId"))
                || !plan.targetId().equals(targetId()) || !plan.physicalTargetId().equals(physicalTargetId()))
            throw new IllegalStateException("Frozen stk_st_daily request or isolated target changed before run");
        var ledger = new SyncRunLedger(ledgerPath);
        var writer = new StockStDailyWritePort(table, plan.physicalTargetId(), jdbc, questdb);
        Path evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        var adapter = new StockStDailySyncAdapter(new StockStDailySource(pages,
                new com.zoutrankil.data.mapper.StockStDailyMapper(), evidence.resolve("source")),
                tradingDates, writer, evidence, runId, ledgerPath, table, plan.targetId(), jdbc, questdb);
        var runner = new SyncJobRunner<StockStDaily,StockStDailyKey>(ledger, new DatasetIntervalLock(ledgerPath));
        java.util.function.BooleanSupplier cancelled = () -> {
            if (Thread.currentThread().isInterrupted()) return true;
            try { return ledger.cancellationRequested(runId); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot read stk_st_daily cancellation state", failure); }
        };
        var result = priorRunId == null ? runner.run(runId, null, plan.targetId(), plan.request(), adapter, cancelled)
                : runner.resume(runId, priorRunId, priorRunId, plan.targetId(), plan.request(), adapter, cancelled);
        var recorded = ledger.get(runId);
        if (recorded.state() == SyncRunState.IN_DOUBT && result.state() != SyncRunState.IN_DOUBT)
            return new SyncJobRunner.Result(result.runId(), SyncRunState.IN_DOUBT, result.sourceRows(),
                    result.verifiedRows(), "D012_PUBLICATION_IN_DOUBT", result.reusedRows());
        return result;
    }
}
