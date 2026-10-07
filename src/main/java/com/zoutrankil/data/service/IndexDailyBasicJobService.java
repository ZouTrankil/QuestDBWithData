package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.policy.IsolatedTablePolicy;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.mapper.IndexDailyBasicMapper;
import com.zoutrankil.data.repository.*;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Explicit bounded D020 planner/runner for one frozen source code and isolated table. */
@Service
public final class IndexDailyBasicJobService {
    public static final String ISOLATED_TABLE_PREFIX = IsolatedTablePolicy.INDEX_DAILY_BASIC.prefix();
    public record Plan(SyncJobDefinition.FrozenRequest request, String targetId, String tsCode,
            LocalDate checkpointBefore, LocalDate checkpointAnchor,
            IndexDailyBasicWritePort.TargetRange physicalRange, boolean bootstrap) {
        public Plan {
            Objects.requireNonNull(request); Objects.requireNonNull(targetId); Objects.requireNonNull(tsCode);
            Objects.requireNonNull(physicalRange);
            if (!targetId.equals(request.parameters().get("targetId"))
                    || !tsCode.equals(request.parameters().get("tsCode")))
                throw new IllegalArgumentException("D020 target/code identity must be frozen in request");
        }
    }
    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final Path ledgerPath;
    private final String table;
    @Autowired
    public IndexDailyBasicJobService(@Lazy SyncJobRegistry jobs, TusharePageService pages, JdbcTemplate jdbc,
            @Lazy QuestDB questdb, @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            @Value("${app.sync.index-daily-basic-table:java_d020_index_daily_basic_acceptance}") String table) {
        this(jobs, pages, jdbc, questdb, Path.of(ledgerPath), table);
    }
    public IndexDailyBasicJobService(SyncJobRegistry jobs, TusharePageService pages, JdbcTemplate jdbc,
            QuestDB questdb, Path ledgerPath, String table) {
        this.jobs = Objects.requireNonNull(jobs); this.pages = Objects.requireNonNull(pages);
        this.jdbc = Objects.requireNonNull(jdbc); this.questdb = Objects.requireNonNull(questdb);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize(); requireIsolatedTableName(table); this.table = table;
    }
    public String tableName() { return table; }
    public static void requireIsolatedTableName(String table) {
        IsolatedTablePolicy.INDEX_DAILY_BASIC.require(table);
    }
    public String targetId() {
        requireIsolatedTableName(table);
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact isolated D020 QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }

    /** `from` is required only for a first bounded incremental bootstrap; later runs derive it from receipts. */
    public Plan plan(Mode requestedMode, String tsCode, LocalDate from, LocalDate requestedThrough,
            LocalDate logicalDate) throws Exception {
        Objects.requireNonNull(logicalDate, "Frozen D020 logical date required");
        String code = IndexDailyBasicSource.requireCode(tsCode);
        if (requestedThrough != null && requestedThrough.isAfter(logicalDate)) throw new IllegalArgumentException("D020 --to exceeds frozen logical date");
        var sourceNow = ZonedDateTime.now(DailySyncEndDate.ZONE);
        LocalDate completedCeiling = DailySyncEndDate.resolve(null, sourceNow);
        LocalDate requestedLimit = requestedThrough == null ? logicalDate : requestedThrough;
        LocalDate to = DailySyncEndDate.resolve(requestedLimit, sourceNow);
        if (to.isAfter(logicalDate)) to = logicalDate;
        var definition = IndexDailyBasicSyncJobOwner.DEFINITION;
        Mode mode = requestedMode == null ? definition.defaultMode() : requestedMode;
        if (!definition.supportedModes().contains(mode)) throw new IllegalArgumentException("Unsupported D020 sync mode");
        if ((mode == Mode.BACKFILL || mode == Mode.RECONCILE) && (from == null || requestedThrough == null))
            throw new IllegalArgumentException("Bounded D020 backfill/reconcile requires explicit --from and --to");
        if (from != null && from.isAfter(to)) throw new IllegalArgumentException("D020 --from is after completed --to");

        String target = targetId();
        var port = new IndexDailyBasicWritePort(table, target, jdbc, questdb); port.preflight();
        var physical = port.readExistingRange(code);
        if (physical.max() != null && physical.max().isAfter(logicalDate)) throw new IllegalStateException("D020 target exceeds frozen logical date");
        var saved = IndexDailyBasicCoverage.checkpoint(ledgerPath, target, code);
        IndexDailyBasicCoverage.validateExistingTarget(ledgerPath, target, code, port);

        LocalDate resolvedFrom = from, anchor = null, checkpointBefore = null; boolean bootstrap = false;
        if (mode == Mode.INCREMENTAL) {
            if (saved.isEmpty()) {
                if (from == null) throw new IllegalArgumentException("Explicit bounded --from bootstrap required without a verified D020 checkpoint");
                if (physical.min() != null) throw new IllegalStateException("Nonempty D020 code target has no receipt-backed incremental checkpoint");
                resolvedFrom = from; anchor = from; bootstrap = true;
            } else {
                if (from != null) throw new IllegalArgumentException("D020 --from is bootstrap-only; use BACKFILL for a second explicit window");
                var checkpoint = saved.get(); checkpointBefore = checkpoint.through(); anchor = checkpoint.anchor();
                if (to.isBefore(checkpointBefore)) throw new IllegalArgumentException("D020 end precedes verified checkpoint");
                resolvedFrom = checkpointBefore.minusDays(IndexDailyBasicSyncJobOwner.REVISION_DAYS);
                if (resolvedFrom.isBefore(anchor)) resolvedFrom = anchor;
                if (physical.max() != null && physical.max().isAfter(completedCeiling))
                    throw new IllegalStateException("D020 physical target contains data beyond completed-source ceiling");
            }
        } else if (mode == Mode.BACKFILL || mode == Mode.RECONCILE) resolvedFrom = Objects.requireNonNull(from);
        else throw new IllegalArgumentException("D020 supports bounded INCREMENTAL, BACKFILL and RECONCILE only");
        long span = ChronoUnit.DAYS.between(resolvedFrom, to) + 1;
        if (span < 1 || span > definition.budget().maxWindowDays()) throw new IllegalArgumentException("Resolved D020 range exceeds 366 days");
        if (mode == Mode.INCREMENTAL && physical.max() != null && physical.max().isAfter(to))
            throw new IllegalStateException("D020 incremental end precedes existing target data");

        var parameters = new LinkedHashMap<String,Object>(); parameters.put("targetId", target); parameters.put("tsCode", code);
        if (mode == Mode.INCREMENTAL) {
            parameters.put("checkpointAnchor", anchor);
            if (checkpointBefore != null) parameters.put("checkpointBefore", checkpointBefore);
        }
        if (physical.min() != null) parameters.put("targetMinBefore", physical.min());
        if (physical.max() != null) parameters.put("targetMaxBefore", physical.max());
        var request = jobs.prepare(definition.jobId(), definition.version(), mode, parameters, resolvedFrom, to, logicalDate);
        new IndexDailyBasicSyncAdapter(new IndexDailyBasicSource(pages, new IndexDailyBasicMapper(),
                ledgerPath.getParent().resolve("sync-evidence").resolve("d020-preflight")), port,
                ledgerPath.getParent()).preflight(request);
        if (!target.equals(targetId())) throw new IllegalStateException("D020 target identity changed while planning");
        return new Plan(request, target, code, checkpointBefore, anchor, physical, bootstrap);
    }
    public SyncJobRunner.Result run(Plan plan) throws Exception { return execute("index-daily-basic-" + UUID.randomUUID(), null, plan); }
    public SyncJobRunner.Result resume(String priorRunId) throws Exception {
        var restored = FrozenRunRequest.restore(ledgerPath, priorRunId, IndexDailyBasicSyncJobOwner.DEFINITION);
        var request = restored.request(); var parameters = request.parameters();
        String code = IndexDailyBasicSource.requireCode((String) parameters.get("tsCode"));
        var physical = new IndexDailyBasicWritePort.TargetRange((LocalDate) parameters.get("targetMinBefore"),
                (LocalDate) parameters.get("targetMaxBefore"));
        var plan = new Plan(request, restored.targetId(), code, (LocalDate) parameters.get("checkpointBefore"),
                (LocalDate) parameters.get("checkpointAnchor"), physical, !parameters.containsKey("checkpointBefore"));
        return resume(plan, priorRunId);
    }
    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        return execute("index-daily-basic-" + UUID.randomUUID(), Objects.requireNonNull(priorRunId), plan);
    }
    public SyncJobRunner.Result runAsGroupChild(String childId, String parentId, String expectedTarget,
            SyncJobDefinition.FrozenRequest request) throws Exception {
        String code = IndexDailyBasicSource.requireCode((String) request.parameters().get("tsCode"));
        if (!targetId().equals(expectedTarget)) throw new IllegalStateException("D020 group target changed");
        return execute(childId, parentId, new Plan(request, expectedTarget, code,
                (LocalDate) request.parameters().get("checkpointBefore"),
                (LocalDate) request.parameters().get("checkpointAnchor"),
                new IndexDailyBasicWritePort.TargetRange((LocalDate) request.parameters().get("targetMinBefore"),
                        (LocalDate) request.parameters().get("targetMaxBefore")), !request.parameters().containsKey("checkpointBefore")));
    }
    private SyncJobRunner.Result execute(String runId, String priorRunId, Plan plan) throws Exception {
        if (!plan.request().definition().equals(IndexDailyBasicSyncJobOwner.DEFINITION)
                || !plan.targetId().equals(plan.request().parameters().get("targetId"))
                || !plan.tsCode().equals(plan.request().parameters().get("tsCode")) || !plan.targetId().equals(targetId()))
            throw new IllegalStateException("Frozen D020 plan/target identity changed before run");
        var ledger = new SyncRunLedger(ledgerPath); var port = new IndexDailyBasicWritePort(table, plan.targetId(), jdbc, questdb);
        if (priorRunId == null) {
            port.preflight();
            if (!plan.physicalRange().equals(port.readExistingRange(plan.tsCode())))
                throw new IllegalStateException("D020 physical target range changed after frozen plan; re-plan against current contents");
        }
        var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        var adapter = new IndexDailyBasicSyncAdapter(new IndexDailyBasicSource(pages, new IndexDailyBasicMapper(), evidence.resolve("source")), port, evidence);
        var runner = new SyncJobRunner<IndexDailyBasic,IndexDailyBasicKey>(ledger, new DatasetIntervalLock(ledgerPath));
        java.util.function.BooleanSupplier cancelled = () -> {
            if (Thread.currentThread().isInterrupted()) return true;
            try { return ledger.cancellationRequested(runId); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot read D020 cancellation state", failure); }
        };
        return priorRunId == null ? runner.run(runId, null, plan.targetId(), plan.request(), adapter, cancelled)
                : runner.resume(runId, priorRunId, priorRunId, plan.targetId(), plan.request(), adapter, cancelled);
    }
}
