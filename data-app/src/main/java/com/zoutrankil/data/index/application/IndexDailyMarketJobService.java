package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.port.IndexDailyMarketTarget;
import com.zoutrankil.data.index.domain.IndexDailyMarketTargetRange;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.policy.IndexDailyMarketUniverse;

import com.zoutrankil.data.domain.policy.IsolatedTablePolicy;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.index.mapper.IndexDailyMarketMapper;
import com.zoutrankil.data.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Explicit bounded planner/runner for one frozen D019 source code and isolated target. */
@Service
public final class IndexDailyMarketJobService {
    public static final String ISOLATED_TABLE_PREFIX = IsolatedTablePolicy.INDEX_DAILY_MARKET.prefix();
    public record Plan(SyncJobDefinition.FrozenRequest request, String targetId, String tsCode,
            IndexDailyMarketUniverse.Route route, LocalDate checkpointBefore, LocalDate checkpointAnchor,
            IndexDailyMarketTargetRange physicalRange, boolean bootstrap) {
        public Plan {
            Objects.requireNonNull(request); Objects.requireNonNull(targetId); Objects.requireNonNull(tsCode);
            Objects.requireNonNull(route); Objects.requireNonNull(physicalRange);
            if (!targetId.equals(request.parameters().get("targetId")) || !tsCode.equals(request.parameters().get("tsCode"))
                    || !route.name().equals(request.parameters().get("route")))
                throw new IllegalArgumentException("D019 target/code/route identity must be frozen in the run request");
        }
    }
    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final IndexDailyMarketTarget target;
    private final Path ledgerPath;
    private final String table;
    @Autowired
    public IndexDailyMarketJobService(@Lazy SyncJobRegistry jobs, TusharePageService pages,
            IndexDailyMarketTarget target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(jobs, pages, target, Path.of(ledgerPath));
    }
    public IndexDailyMarketJobService(SyncJobRegistry jobs, TusharePageService pages,
            IndexDailyMarketTarget target, Path ledgerPath) {
        this.jobs = Objects.requireNonNull(jobs); this.pages = Objects.requireNonNull(pages);
        this.target = Objects.requireNonNull(target);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
        String table = target.tableName(); requireIsolatedTableName(table); this.table = table;
    }
    public String tableName() { return table; }
    public static void requireIsolatedTableName(String table) {
        IsolatedTablePolicy.INDEX_DAILY_MARKET.require(table);
    }
    public String targetId() { return target.targetId(); }

    /** Build an immutable per-code request. `from` is bootstrap-only for INCREMENTAL. */
    public Plan plan(Mode requestedMode, String tsCode, LocalDate from, LocalDate requestedThrough,
            LocalDate logicalDate) throws Exception {
        Objects.requireNonNull(logicalDate, "Frozen D019 logical date required");
        var index = IndexDailyMarketUniverse.resolve(tsCode);
        if (index == null) throw new IllegalArgumentException("D019 code must be selected from CORE57 or SW2021_L1_31");
        if (requestedThrough != null && requestedThrough.isAfter(logicalDate))
            throw new IllegalArgumentException("D019 --to exceeds frozen logical date");
        var sourceNow = ZonedDateTime.now(DailySyncEndDate.ZONE);
        LocalDate completedCeiling = DailySyncEndDate.resolve(null, sourceNow);
        LocalDate requestedLimit = requestedThrough == null ? logicalDate : requestedThrough;
        LocalDate to = DailySyncEndDate.resolve(requestedLimit, sourceNow);
        if (to.isAfter(logicalDate)) to = logicalDate;
        var definition = IndexDailyMarketSyncJobOwner.DEFINITION;
        Mode mode = requestedMode == null ? definition.defaultMode() : requestedMode;
        if (!definition.supportedModes().contains(mode)) throw new IllegalArgumentException("Unsupported D019 sync mode");
        if ((mode == Mode.BACKFILL || mode == Mode.RECONCILE) && (from == null || requestedThrough == null))
            throw new IllegalArgumentException("Bounded D019 backfill/reconcile requires explicit --from and --to");
        if (from != null && from.isAfter(to)) throw new IllegalArgumentException("D019 --from is after the completed --to");

        String target = targetId();
        var port = this.target.newWriter(target); port.preflight();
        var physical = port.readExistingRange(index.tsCode());
        if (physical.max() != null && physical.max().isAfter(logicalDate))
            throw new IllegalStateException("D019 target contains a code date after the frozen logical date");
        var saved = IndexDailyMarketCoverage.checkpoint(ledgerPath, target, index.tsCode());
        IndexDailyMarketCoverage.validateExistingTarget(ledgerPath, target, index.tsCode(), port);

        LocalDate resolvedFrom = from, anchor = null, checkpointBefore = null;
        boolean bootstrap = false;
        if (mode == Mode.INCREMENTAL) {
            if (saved.isEmpty()) {
                if (from == null) throw new IllegalArgumentException("Explicit bounded --from bootstrap required without a verified D019 code checkpoint");
                if (physical.min() != null)
                    throw new IllegalStateException("Nonempty D019 code target has no receipt-backed incremental checkpoint");
                resolvedFrom = from; anchor = from; bootstrap = true;
            } else {
                if (from != null) throw new IllegalArgumentException("D019 --from is bootstrap-only; use BACKFILL for a second explicit window");
                var checkpoint = saved.get(); checkpointBefore = checkpoint.through(); anchor = checkpoint.anchor();
                if (to.isBefore(checkpointBefore)) throw new IllegalArgumentException("D019 end precedes its verified checkpoint");
                resolvedFrom = checkpointBefore.minusDays(IndexDailyMarketSyncJobOwner.REVISION_DAYS);
                if (resolvedFrom.isBefore(anchor)) resolvedFrom = anchor;
                if (physical.max() != null && physical.max().isAfter(completedCeiling))
                    throw new IllegalStateException("D019 target contains a partial-day value beyond the completed-source ceiling");
            }
        } else if (mode == Mode.BACKFILL || mode == Mode.RECONCILE) {
            resolvedFrom = Objects.requireNonNull(from);
        } else throw new IllegalArgumentException("D019 supports only bounded INCREMENTAL, BACKFILL and RECONCILE");
        long days = ChronoUnit.DAYS.between(resolvedFrom, to) + 1;
        if (days < 1 || days > definition.budget().maxWindowDays())
            throw new IllegalArgumentException("Resolved D019 date window exceeds 366-day bound");
        if (mode == Mode.INCREMENTAL && physical.max() != null && physical.max().isAfter(to))
            throw new IllegalStateException("Requested D019 range ends before the existing physical code data");

        String observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS).toString();
        var parameters = new LinkedHashMap<String,Object>();
        parameters.put("targetId", target); parameters.put("tsCode", index.tsCode());
        parameters.put("route", index.route().name()); parameters.put("observedAt", observedAt);
        if (mode == Mode.INCREMENTAL) {
            parameters.put("checkpointAnchor", anchor);
            if (checkpointBefore != null) parameters.put("checkpointBefore", checkpointBefore);
        }
        if (physical.min() != null) parameters.put("targetMinBefore", physical.min());
        if (physical.max() != null) parameters.put("targetMaxBefore", physical.max());
        var request = jobs.prepare(definition.jobId(), definition.version(), mode, parameters, resolvedFrom, to, logicalDate);
        new IndexDailyMarketSyncAdapter(new IndexDailyMarketSource(pages, new IndexDailyMarketMapper(),
                ledgerPath.getParent().resolve("sync-evidence").resolve("d019-preflight")), port,
                ledgerPath.getParent()).preflight(request);
        if (!target.equals(targetId())) throw new IllegalStateException("D019 isolated target identity changed while planning");
        return new Plan(request, target, index.tsCode(), index.route(), checkpointBefore, anchor, physical, bootstrap);
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception { return execute("index-daily-market-" + UUID.randomUUID(), null, plan); }
    public SyncJobRunner.Result resume(String priorRunId) throws Exception {
        var restored = FrozenRunRequest.restore(ledgerPath, priorRunId, IndexDailyMarketSyncJobOwner.DEFINITION);
        var request = restored.request(); var parameters = request.parameters();
        var index = IndexDailyMarketUniverse.resolve((String) parameters.get("tsCode"));
        if (index == null) throw new IllegalStateException("Frozen D019 code is no longer in the universe");
        var physical = new IndexDailyMarketTargetRange((LocalDate) parameters.get("targetMinBefore"),
                (LocalDate) parameters.get("targetMaxBefore"));
        var plan = new Plan(request, restored.targetId(), index.tsCode(), index.route(),
                (LocalDate) parameters.get("checkpointBefore"), (LocalDate) parameters.get("checkpointAnchor"),
                physical, !parameters.containsKey("checkpointBefore"));
        return resume(plan, priorRunId);
    }
    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        return execute("index-daily-market-" + UUID.randomUUID(), Objects.requireNonNull(priorRunId), plan);
    }
    public SyncJobRunner.Result runAsGroupChild(String childId, String parentId, String expectedTarget,
            SyncJobDefinition.FrozenRequest request) throws Exception {
        var index = IndexDailyMarketUniverse.resolve((String) request.parameters().get("tsCode"));
        if (index == null || !targetId().equals(expectedTarget)) throw new IllegalStateException("D019 group target/code changed");
        return execute(childId, parentId, new Plan(request, expectedTarget, index.tsCode(), index.route(),
                (LocalDate) request.parameters().get("checkpointBefore"), (LocalDate) request.parameters().get("checkpointAnchor"),
                new IndexDailyMarketTargetRange((LocalDate) request.parameters().get("targetMinBefore"),
                        (LocalDate) request.parameters().get("targetMaxBefore")), !request.parameters().containsKey("checkpointBefore")));
    }
    private SyncJobRunner.Result execute(String runId, String priorRunId, Plan plan) throws Exception {
        if (!plan.request().definition().equals(IndexDailyMarketSyncJobOwner.DEFINITION)
                || !plan.targetId().equals(plan.request().parameters().get("targetId"))
                || !plan.targetId().equals(targetId())) throw new IllegalStateException("Frozen D019 plan or target identity changed before run");
        return SyncRunExecution.execute(ledgerPath, runId, priorRunId, plan.targetId(), plan.request(),
                "Cannot read D019 cancellation state", () -> {
            var port = this.target.newWriter(plan.targetId());
            var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
            return new IndexDailyMarketSyncAdapter(new IndexDailyMarketSource(pages, new IndexDailyMarketMapper(), evidence.resolve("source")), port, evidence);
        });
    }
}
