package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode;
import com.zoutrankil.questdbwithdata.repository.*;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Explicit bounded plan/run/resume/status owner for D014's externally owned etf_daily table. */
@Service
public final class EtfDailyJobService {
    public static final String ISOLATED_TABLE_PREFIX = "java_d014_etf_daily_";
    public record Plan(SyncJobDefinition.FrozenRequest request, String targetId, LocalDate checkpointBefore,
                       LocalDate checkpointAnchor, LocalDate targetMinDate, LocalDate targetMaxDate,
                       boolean bootstrap) {
        public Plan {
            Objects.requireNonNull(request); Objects.requireNonNull(targetId);
            if (!targetId.equals(request.parameters().get("targetId")))
                throw new IllegalArgumentException("etf_daily target identity must be frozen in the run request");
        }
    }
    private record TargetRange(LocalDate min, LocalDate max) {
        TargetRange {
            if ((min == null) != (max == null) || min != null && min.isAfter(max))
                throw new IllegalStateException("Invalid etf_daily physical range");
        }
    }
    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final EtfDailyTradingDates tradingDates;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final Path ledgerPath;
    private final String table;

    @Autowired
    public EtfDailyJobService(@Lazy SyncJobRegistry jobs, TusharePageService pages,
            ExchangeCalendarReadRepository calendars, JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            @Value("${app.sync.etf-daily-table:java_d014_etf_daily_acceptance}") String table) {
        this(jobs, pages, calendars, jdbc, questdb, Path.of(ledgerPath), table);
    }
    public EtfDailyJobService(SyncJobRegistry jobs, TusharePageService pages,
            ExchangeCalendarReadRepository calendars, JdbcTemplate jdbc, QuestDB questdb,
            Path ledgerPath, String table) {
        this.jobs = Objects.requireNonNull(jobs); this.pages = Objects.requireNonNull(pages);
        this.tradingDates = new EtfDailyTradingDates(calendars); this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize(); requireIsolatedTableName(table); this.table = table;
    }

    public String tableName() { return table; }

    public static void requireIsolatedTableName(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_TABLE_PREFIX) || table.length() <= ISOLATED_TABLE_PREFIX.length())
            throw new IllegalStateException("D014 execution requires a dedicated java_d014_etf_daily_<suffix> isolated target");
    }

    public String targetId() throws Exception {
        requireIsolatedTableName(table);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact isolated etf_daily QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }

    /** Builds a frozen daily plan. Incremental uses receipt-backed coverage and a five-day revision overlap. */
    public Plan plan(Mode requestedMode, LocalDate bootstrapFrom, LocalDate requestedThrough, LocalDate logicalDate) throws Exception {
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
        var port = new EtfDailyWritePort(table, target, jdbc, questdb);
        port.preflight();
        TargetRange physical = readTargetRange();
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
        new EtfDailySyncAdapter(new EtfDailySource(pages, new com.zoutrankil.questdbwithdata.mapper.EtfDailyMapper(),
                ledgerPath.getParent().resolve("sync-evidence").resolve("etf-daily-preflight")),
                tradingDates, port, ledgerPath.getParent()).preflight(request);
        if (!target.equals(targetId())) throw new IllegalStateException("etf_daily target identity changed while planning");
        return new Plan(request, target, checkpointBefore, anchor, physical.min(), physical.max(), bootstrap);
    }

    private TargetRange readTargetRange() {
        String sql = "SELECT cast(min(timestamp) AS long) AS min_nanos, cast(max(timestamp) AS long) AS max_nanos FROM \"" + table + "\"";
        return jdbc.query(sql, rs -> {
            if (!rs.next()) throw new IllegalStateException("QuestDB did not return etf_daily date range");
            Object min = rs.getObject("min_nanos"), max = rs.getObject("max_nanos");
            if (min == null && max == null) return new TargetRange(null, null);
            if (!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                throw new IllegalStateException("QuestDB etf_daily date range has invalid types");
            var from = com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(minValue.longValue(), com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.EpochUnit.NANOS).date();
            var to = com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(maxValue.longValue(), com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.EpochUnit.NANOS).date();
            return new TargetRange(from, to);
        });
    }

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
        if (!plan.request().definition().equals(EtfDailySyncJobOwner.DEFINITION)
                || !plan.targetId().equals(plan.request().parameters().get("targetId"))
                || !plan.targetId().equals(targetId()))
            throw new IllegalStateException("Frozen etf_daily plan or target identity changed before run");
        var ledger = new SyncRunLedger(ledgerPath);
        var port = new EtfDailyWritePort(table, plan.targetId(), jdbc, questdb);
        var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        var adapter = new EtfDailySyncAdapter(new EtfDailySource(pages,
                new com.zoutrankil.questdbwithdata.mapper.EtfDailyMapper(), evidence.resolve("source")),
                tradingDates, port, evidence);
        var runner = new SyncJobRunner<EtfDaily,EtfDailyKey>(ledger, new DatasetIntervalLock(ledgerPath));
        java.util.function.BooleanSupplier cancelled = () -> {
            if (Thread.currentThread().isInterrupted()) return true;
            try { return ledger.cancellationRequested(runId); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot read etf_daily cancellation state", failure); }
        };
        return priorRunId == null ? runner.run(runId, null, plan.targetId(), plan.request(), adapter, cancelled)
                : runner.resume(runId, priorRunId, priorRunId, plan.targetId(), plan.request(), adapter, cancelled);
    }
}
