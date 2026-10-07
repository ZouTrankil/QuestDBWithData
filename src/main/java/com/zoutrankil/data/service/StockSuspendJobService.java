package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Explicit plan/run/resume/status/cancel entry for one isolated, bounded stk_suspend job. */
@Service
public final class StockSuspendJobService {
    public record Plan(FrozenRequest request, String targetId, String physicalTargetId,
                       LocalDate checkpointBefore, LocalDate checkpointAnchor,
                       LocalDate targetMinDate, LocalDate targetMaxDate, LocalDate requestedThrough,
                       boolean bootstrap, boolean cappedByBudget) {
        public Plan {
            Objects.requireNonNull(request); Objects.requireNonNull(targetId); Objects.requireNonNull(physicalTargetId);
            if (!targetId.equals(request.parameters().get("targetId")))
                throw new IllegalArgumentException("stk_suspend target identity must be frozen in plan request");
            if (!physicalTargetId.equals(request.parameters().get("physicalTargetId")))
                throw new IllegalArgumentException("stk_suspend physical identity must be frozen in plan request");
        }
    }
    record BootstrapWindow(LocalDate from, LocalDate to, boolean cappedByBudget) {}
    private record TargetRange(LocalDate min, LocalDate max) {
        private TargetRange {
            if ((min == null) != (max == null) || min != null && min.isAfter(max))
                throw new IllegalStateException("Invalid stk_suspend physical date range");
        }
    }

    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final String table;
    private final Path ledgerPath;

    @Autowired
    public StockSuspendJobService(@Lazy SyncJobRegistry jobs, TusharePageService pages, JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            @Value("${app.sync.stk-suspend-table:stk_suspend_d011_isolated}") String table) {
        this(jobs, pages, jdbc, questdb, Path.of(ledgerPath), table);
    }

    public StockSuspendJobService(SyncJobRegistry jobs, TusharePageService pages, JdbcTemplate jdbc, QuestDB questdb,
                                  Path ledgerPath, String table) {
        this.jobs = Objects.requireNonNull(jobs); this.pages = Objects.requireNonNull(pages);
        this.jdbc = Objects.requireNonNull(jdbc); this.questdb = Objects.requireNonNull(questdb);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
        requireAdmittedTableName(table);
        this.table = table;
    }

    public static void requireAdmittedTableName(String table) {
        DatasetDefinition.identifier(table);
        if (!"stk_suspend".equals(table) && !table.matches("(?:java_d011_stk_suspend_|stk_suspend_d011_)[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("D011 target must be exact formal stk_suspend or an explicitly isolated suspension target");
    }

    private void requireAdmittedMode(Mode mode) {
        if ("stk_suspend".equals(table) && mode != Mode.BACKFILL)
            throw new IllegalArgumentException("Formal stk_suspend requires an explicit source-certified five-day BACKFILL");
    }

    public String tableName() { return table; }
    public void requireNoPendingPublication() throws Exception {
        StockSuspendPublication.requireNoPendingPublication(ledgerPath);
    }

    /** Incremental planning requires a verified checkpoint or an explicit bounded bootstrap start. */
    public Plan planDetailed(Mode requestedMode, LocalDate bootstrapFrom, LocalDate requestedThrough,
                             LocalDate logicalDate) throws Exception {
        Objects.requireNonNull(requestedThrough, "stk_suspend upper date required");
        Objects.requireNonNull(logicalDate, "stk_suspend logical date required");
        Mode mode = requestedMode == null ? StockSuspendSyncJobOwner.DEFINITION.defaultMode() : requestedMode;
        if (!StockSuspendSyncJobOwner.DEFINITION.supportedModes().contains(mode))
            throw new IllegalArgumentException("Unsupported stk_suspend sync mode");
        requireAdmittedMode(mode);
        if (requestedThrough.isAfter(logicalDate)) throw new IllegalArgumentException("stk_suspend window cannot exceed logicalDate");
        if ("stk_suspend".equals(table) && requestedThrough.isAfter(DailySyncEndDate.resolve(null,
                java.time.ZonedDateTime.now(DailySyncEndDate.ZONE))))
            throw new IllegalArgumentException("Formal stk_suspend cannot include an incomplete source date");

        String target = targetId();
        String frozenPhysical = physicalTargetId();
        if (java.nio.file.Files.isRegularFile(ledgerPath))
            StockSuspendPublication.verifyCurrentTarget(ledgerPath,jdbc,table,target,frozenPhysical);
        TargetRange physical = readTargetRange();
        requireSameTarget(target, targetId());requireSameTarget(frozenPhysical,physicalTargetId());
        if (physical.max() != null && physical.max().isAfter(logicalDate))
            throw new IllegalStateException("stk_suspend physical target contains a date after logicalDate");
        LocalDate from = bootstrapFrom, to = requestedThrough, checkpointBefore = null, anchor = null;
        boolean bootstrap = false, capped = false;
        var parameters = new LinkedHashMap<String,Object>();
        parameters.put("targetId", target);
        parameters.put("physicalTargetId",frozenPhysical);
        if (physical.min() != null) parameters.put("targetMinBefore", physical.min());
        if (physical.max() != null) parameters.put("targetMaxBefore", physical.max());

        if (mode == Mode.INCREMENTAL) {
            Optional<StockSuspendCoverage.Coverage> saved = StockSuspendCoverage.checkpoint(ledgerPath, target);
            if (saved.isEmpty()) {
                if (bootstrapFrom == null)
                    throw new IllegalArgumentException("Explicit bounded bootstrap start required before verified stk_suspend checkpoint exists");
                if (bootstrapFrom.isAfter(requestedThrough)) throw new IllegalArgumentException("Bootstrap start is after requested end");
                BootstrapWindow window = resolveBootstrapWindow(bootstrapFrom, requestedThrough, physical.max(),
                        logicalDate, StockSuspendSyncJobOwner.DEFINITION.budget().maxWindowDays());
                from = window.from(); to = window.to(); capped = window.cappedByBudget(); anchor = bootstrapFrom; bootstrap = true;
            } else {
                if (bootstrapFrom != null)
                    throw new IllegalArgumentException("Bootstrap start only applies before a verified checkpoint; use BACKFILL for another range");
                var coverage = saved.get(); checkpointBefore = coverage.through(); anchor = coverage.anchor();
                if (requestedThrough.isBefore(checkpointBefore))
                    throw new IllegalArgumentException("Requested stk_suspend end precedes its verified checkpoint");
                parameters.put("checkpointBefore", checkpointBefore);
                from = checkpointBefore.minusDays(StockSuspendSyncJobOwner.REVISION_DAYS);
                LocalDate targetThrough = physical.max() != null && physical.max().isAfter(requestedThrough)
                        ? physical.max() : requestedThrough;
                if (targetThrough.isAfter(logicalDate))
                    throw new IllegalStateException("Existing stk_suspend date exceeds requested logicalDate");
                LocalDate lastAllowed = from.plusDays(StockSuspendSyncJobOwner.MAX_WINDOW_DAYS - 1L);
                to = targetThrough.isAfter(lastAllowed) ? lastAllowed : targetThrough;
                capped = to.isBefore(targetThrough);
                if (to.isBefore(checkpointBefore)) throw new IllegalStateException("Resolved overlap ends before verified checkpoint");
            }
            parameters.put("checkpointAnchor", anchor);
        } else {
            if (bootstrapFrom == null || bootstrapFrom.isAfter(requestedThrough))
                throw new IllegalArgumentException("Explicit bounded from/to required for stk_suspend BACKFILL");
            from = bootstrapFrom; to = requestedThrough;
            if (to.isAfter(from.plusDays(StockSuspendSyncJobOwner.MAX_WINDOW_DAYS - 1L)))
                throw new IllegalArgumentException("BACKFILL must fit one five-day request window");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        if (days < 1 || days > StockSuspendSyncJobOwner.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("Resolved stk_suspend window exceeds the five-day budget");
        FrozenRequest request = jobs.prepare(StockSuspendSyncJobOwner.DEFINITION.jobId(),
                StockSuspendSyncJobOwner.DEFINITION.version(), mode, parameters, from, to, logicalDate);
        StockSuspendSyncAdapter.validateRequest(request);
        return new Plan(request, target, frozenPhysical, checkpointBefore, anchor, physical.min(), physical.max(), requestedThrough,
                bootstrap, capped);
    }

    public FrozenRequest plan(Mode mode, LocalDate from, LocalDate to, LocalDate logicalDate) throws Exception {
        return planDetailed(mode, from, to, logicalDate).request();
    }

    /** Restore the exact historical request, including its physical table generation, for recovery. */
    public FrozenRequest restorePlan(String runId)throws Exception {
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=ledger.getRun(runId);
        if(!run.jobId().equals(StockSuspendSyncJobOwner.DEFINITION.jobId())
                ||run.jobVersion()!=StockSuspendSyncJobOwner.DEFINITION.version()
                ||!run.targetId().equals(targetId()))
            throw new IllegalArgumentException("Run is not a frozen request for this logical stk_suspend target");
        FrozenRequest request=FrozenRunRequest.restore(ledgerPath,runId,StockSuspendSyncJobOwner.DEFINITION).request();
        validate(request);requireSameTarget(run.targetId(),frozenTargetId(request));
        return request;
    }

    private TargetRange readTargetRange() {
        String sql = "SELECT cast(min(timestamp) AS long) AS min_micros,cast(max(timestamp) AS long) AS max_micros FROM \"" + table + "\"";
        return jdbc.query(sql, rs -> {
            if (!rs.next()) throw new IllegalStateException("QuestDB did not return stk_suspend date range aggregate");
            Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
            if (min == null && max == null) return new TargetRange(null, null);
            if (!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                throw new IllegalStateException("QuestDB stk_suspend range is not a timestamp epoch");
            return new TargetRange(com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(minValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date(),
                    com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                            .fromStorageEpoch(maxValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date());
        });
    }

    /** Stable logical identity; physical table generations are frozen separately. */
    public String targetId() { return StockSuspendTargetIdentity.logical(jdbc,table); }

    public String physicalTargetId() {
        QuestDbWriteChecks.preflight(jdbc, table, StockSuspendDataset.definition(table));
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact isolated stk_suspend QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }

    public SyncJobRunner.Result run(FrozenRequest request) throws Exception {
        requireAdmittedMode(request.mode());
        validate(request); String expected = frozenTargetId(request);String expectedPhysical=frozenPhysicalTargetId(request);
        requireSameTarget(expected, targetId());requireSameTarget(expectedPhysical,physicalTargetId());
        StockSuspendPublication.verifyCurrentTarget(ledgerPath,jdbc,table,expected,expectedPhysical);
        String run = "stk-suspend-" + UUID.randomUUID();
        return execute(run, null, null, request, expected,expectedPhysical);
    }

    public SyncJobRunner.Result resume(FrozenRequest request, String priorRun) throws Exception {
        requireAdmittedMode(request.mode());
        validate(request);
        SyncRunLedger ledger = SyncRunLedger.openReadOnly(ledgerPath);
        var prior = ledger.getRun(priorRun); var entry = ledger.get(priorRun);
        String expected = frozenTargetId(request);
        if (!Set.of(SyncRunState.FAILED, SyncRunState.CANCELLED, SyncRunState.PARTIAL).contains(entry.state())
                || !prior.jobId().equals(request.definition().jobId()) || prior.jobVersion() != request.definition().version()
                || !expected.equals(prior.targetId())
                || !SyncRequestIdentity.fingerprint(prior.frozenJson(),prior.targetId())
                        .equals(SyncRequestIdentity.fingerprint(request,expected))
                || new DatasetIntervalLock(ledgerPath).findOwned(priorRun,
                        new DatasetIntervalLock.Scope("stk_suspend", request.from(), request.to())) != null)
            throw new IllegalStateException("Reconcile uncertain stk_suspend writes before resuming");
        String frozenPhysical=frozenPhysicalTargetId(request);
        requireSameTarget(expected, prior.targetId()); requireSameTarget(expected, targetId());
        StockSuspendPublication.recoverIfPresent(ledgerPath,jdbc,table,priorRun,true);
        String currentPhysical=physicalTargetId();
        if(!frozenPhysical.equals(currentPhysical)) {
            StockSuspendPublication.finish(ledgerPath,jdbc,table,priorRun,true);
            currentPhysical=physicalTargetId();
            if(!StockSuspendPublication.authorizesResume(ledgerPath,priorRun,expected,frozenPhysical,currentPhysical))
                throw new IllegalStateException("stk_suspend resume physical target transition is not journal verified");
        } else StockSuspendPublication.verifyCurrentTarget(ledgerPath,jdbc,table,expected,currentPhysical);
        String run = "stk-suspend-" + UUID.randomUUID();
        return execute(run, priorRun, priorRun, request, expected,currentPhysical);
    }

    public SyncJobRunner.Result resume(String priorRun)throws Exception {
        return resume(restorePlan(priorRun),priorRun);
    }

    public SyncRunLedger.Entry status(String runId) throws Exception { return SyncRunLedger.openReadOnly(ledgerPath).get(runId); }
    public List<SyncRunLedger.Entry> entries(String runId, String afterId, int limit) throws Exception {
        return SyncRunLedger.openReadOnly(ledgerPath).entries(runId, afterId, limit);
    }
    public boolean cancel(String runId) throws Exception { return new SyncRunLedger(ledgerPath).requestCancellation(runId); }

    public void finishPublication(String runId,boolean writerStopped)throws Exception {
        var state=SyncRunLedger.openReadOnly(ledgerPath).get(runId).state();
        if(state==SyncRunState.VERIFIED||state==SyncRunState.VERIFIED_EMPTY) {
            StockSuspendPublication.finish(ledgerPath,jdbc,table,runId,writerStopped);
            return;
        }
        StockSuspendRunRecovery.finishInterrupted(jdbc,ledgerPath,table,runId,writerStopped);
    }

    private SyncJobRunner.Result execute(String run, String parent, String prior, FrozenRequest request,
                                         String expectedLogical,String expectedPhysical) throws Exception {
        requireAdmittedMode(request.mode());
        requireSameTarget(expectedLogical, targetId());requireSameTarget(expectedPhysical,physicalTargetId());
        var ledger = new SyncRunLedger(ledgerPath);
        var port = new StockSuspendWritePort(table, jdbc, questdb, expectedPhysical);
        var adapter = new StockSuspendSyncAdapter(pages, port,
                ledgerPath.getParent().resolve("sync-evidence").resolve(run), ledgerPath, table, run,
                expectedLogical, expectedPhysical, jdbc);
        var runner = new SyncJobRunner<StockSuspend,StockSuspendKey>(ledger, new DatasetIntervalLock(ledgerPath));
        return prior == null ? runner.run(run, parent, expectedLogical, request, adapter, () -> Thread.currentThread().isInterrupted())
                : runner.resume(run, parent, prior, expectedLogical, request, adapter, () -> Thread.currentThread().isInterrupted());
    }

    private static void validate(FrozenRequest request) { StockSuspendSyncAdapter.validateRequest(request); }
    private static String frozenTargetId(FrozenRequest request) {
        Object value = request.parameters().get("targetId");
        if (!(value instanceof String target)) throw new IllegalArgumentException("Frozen stk_suspend target identity required");
        return target;
    }
    private static String frozenPhysicalTargetId(FrozenRequest request) {
        Object value=request.parameters().get("physicalTargetId");
        if(!(value instanceof String target)||!target.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen stk_suspend physical target identity required");
        return target;
    }
    static void requireSameTarget(String expected, String actual) {
        if (expected == null || actual == null || !expected.equals(actual))
            throw new IllegalStateException("stk_suspend physical target differs from frozen plan");
    }

    static BootstrapWindow resolveBootstrapWindow(LocalDate bootstrapFrom, LocalDate requestedThrough,
                                                   LocalDate physicalMax, LocalDate logicalDate, int maxWindowDays) {
        Objects.requireNonNull(bootstrapFrom); Objects.requireNonNull(requestedThrough); Objects.requireNonNull(logicalDate);
        if (maxWindowDays < 1 || bootstrapFrom.isAfter(requestedThrough))
            throw new IllegalArgumentException("Explicit valid stk_suspend bootstrap window required");
        if (requestedThrough.isAfter(logicalDate) || physicalMax != null && physicalMax.isAfter(logicalDate))
            throw new IllegalStateException("stk_suspend bootstrap ceiling exceeds logicalDate");
        LocalDate targetThrough = physicalMax != null && physicalMax.isAfter(requestedThrough) ? physicalMax : requestedThrough;
        LocalDate lastAllowed = bootstrapFrom.plusDays(maxWindowDays - 1L);
        LocalDate to = targetThrough.isAfter(lastAllowed) ? lastAllowed : targetThrough;
        return new BootstrapWindow(bootstrapFrom, to, to.isBefore(targetThrough));
    }
}
