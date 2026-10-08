package com.zoutrankil.data.index.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.policy.IsolatedTablePolicy;

import com.zoutrankil.data.domain.IndexWeight;
import com.zoutrankil.data.domain.IndexWeightDataset;
import com.zoutrankil.data.domain.IndexWeightKey;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.index.mapper.IndexWeightMapper;
import com.zoutrankil.data.index.port.IndexWeightTarget;
import com.zoutrankil.data.index.domain.IndexWeightTargetRange;
import com.zoutrankil.data.repository.SqliteLedgerSchema;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** D021 frozen plan/run/resume/status/cancel owner. The public index_weight table is never created or migrated here. */
@Service
public final class IndexWeightJobService {
    public record Plan(SyncJobDefinition.FrozenRequest request, String targetId,
            LocalDate targetMinBefore, LocalDate targetMaxBefore,
            LocalDate lastRefreshDate, LocalDate nextRefreshDate, boolean notDue) {
        public Plan {
            Objects.requireNonNull(request); Objects.requireNonNull(targetId);
            if (!targetId.equals(request.parameters().get("targetId")))
                throw new IllegalArgumentException("Frozen D021 target identity mismatch");
            if (!Objects.equals(targetMinBefore, request.parameters().get("targetMinBefore"))
                    || !Objects.equals(targetMaxBefore, request.parameters().get("targetMaxBefore")))
                throw new IllegalArgumentException("Frozen D021 target range mismatch");
            if (notDue && (request.mode() != Mode.SNAPSHOT || Boolean.TRUE.equals(request.parameters().get("force"))
                    || lastRefreshDate == null || nextRefreshDate == null || !request.logicalDate().isBefore(nextRefreshDate)))
                throw new IllegalArgumentException("Invalid D021 refresh gate");
        }
    }

    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final IndexWeightTarget target;
    private final IndexWeightNameResolver nameResolver;
    private final Path ledgerPath;
    private final String table;

    @Autowired
    public IndexWeightJobService(@Lazy SyncJobRegistry jobs, TusharePageService pages,
            IndexWeightTarget target, IndexWeightNameResolver nameResolver,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(jobs, pages, target, nameResolver, Path.of(ledgerPath));
    }
    public IndexWeightJobService(SyncJobRegistry jobs, TusharePageService pages,
            IndexWeightTarget target, IndexWeightNameResolver nameResolver, Path ledgerPath) {
        this.jobs = Objects.requireNonNull(jobs); this.pages = Objects.requireNonNull(pages);
        this.target = Objects.requireNonNull(target);
        this.nameResolver = Objects.requireNonNull(nameResolver);
        this.ledgerPath = Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();
        String table = target.tableName(); requireIsolatedTableName(table); this.table = table;
    }
    public String tableName() { return table; }

    public static void requireIsolatedTableName(String value) {
        IsolatedTablePolicy.INDEX_WEIGHT.require(value);
    }
    public static void requireIsolatedTargetParameter(Map<String, ?> parameters) {
        Object value = parameters == null ? null : parameters.get("targetId");
        if (!(value instanceof String text) || !text.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D021 isolated target identity required");
    }

    public String targetId() { return target.targetId(); }

    /** SNAPSHOT represents the unbounded latest-source route; BACKFILL is an explicit 1..92-day interval. */
    public Plan plan(Mode requestedMode, LocalDate from, LocalDate to, LocalDate logicalDate, boolean force) throws Exception {
        Objects.requireNonNull(logicalDate, "Frozen D021 logical date required");
        Mode mode = requestedMode == null ? IndexWeightSyncJobOwner.DEFINITION.defaultMode() : requestedMode;
        if (!IndexWeightSyncJobOwner.DEFINITION.supportedModes().contains(mode))
            throw new IllegalArgumentException("Unsupported D021 sync mode");
        if (mode == Mode.SNAPSHOT) {
            if (from != null || to != null) throw new IllegalArgumentException("D021 SNAPSHOT does not accept --from/--to");
        } else {
            if (from == null || to == null || from.isAfter(to) || to.isAfter(logicalDate)
                    || java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > IndexWeightSyncJobOwner.MAX_BACKFILL_DAYS)
                throw new IllegalArgumentException("D021 BACKFILL requires explicit --from/--to within 1..92 days through logicalDate");
        }

        String target = targetId();
        var writer = this.target.newWriter(target);
        writer.preflight();
        IndexWeightTargetRange range = writer.readExistingRange();
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("targetId", target);
        parameters.put("stockDetailTargetId", nameResolver.targetId());
        parameters.put("observedAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
        parameters.put("force", force);
        if (range.min() != null) {
            parameters.put("targetMinBefore", range.min()); parameters.put("targetMaxBefore", range.max());
        }
        var request = jobs.prepare(IndexWeightSyncJobOwner.DEFINITION.jobId(), IndexWeightSyncJobOwner.DEFINITION.version(),
                mode, parameters, mode == Mode.SNAPSHOT ? null : from, mode == Mode.SNAPSHOT ? null : to, logicalDate);
        new IndexWeightSyncAdapter(new IndexWeightSource(pages, new IndexWeightMapper(), evidenceRoot("plan"), nameResolver),
                writer, evidenceRoot("plan")).preflight(request);
        if (!target.equals(targetId())) throw new IllegalStateException("D021 physical target changed while planning");
        LocalDate lastRefresh = latestVerifiedSnapshotDate(target);
        LocalDate nextRefresh = lastRefresh == null ? null : lastRefresh.plusDays(7);
        boolean notDue = mode == Mode.SNAPSHOT && !force && nextRefresh != null && logicalDate.isBefore(nextRefresh);
        return new Plan(request, target, range.min(), range.max(), lastRefresh, nextRefresh, notDue);
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception {
        if (plan.notDue()) throw new IllegalStateException("D021 refresh not due until " + plan.nextRefreshDate());
        return execute("index-weight-" + UUID.randomUUID(), null, plan);
    }

    public SyncJobRunner.Result resume(String priorRunId) throws Exception {
        var saved = FrozenRunRequest.restore(ledgerPath, Objects.requireNonNull(priorRunId),
                IndexWeightSyncJobOwner.DEFINITION);
        var parameters = saved.request().parameters();
        var plan = new Plan(saved.request(), saved.targetId(), (LocalDate) parameters.get("targetMinBefore"),
                (LocalDate) parameters.get("targetMaxBefore"), null, null, false);
        return resume(plan, priorRunId);
    }

    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        var saved = FrozenRunRequest.restore(ledgerPath, Objects.requireNonNull(priorRunId),
                IndexWeightSyncJobOwner.DEFINITION);
        if (!SyncRequestIdentity.fingerprint(saved.request(), saved.targetId()).equals(
                SyncRequestIdentity.fingerprint(plan.request(), plan.targetId())))
            throw new IllegalArgumentException("D021 resume requires exact saved request and target identity");
        return execute("index-weight-" + UUID.randomUUID(), priorRunId, plan);
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
        Objects.requireNonNull(plan);
        validatePlan(plan);
        if (priorRunId == null) validateBaseline(plan);
        if (priorRunId == null && plan.request().mode() == Mode.SNAPSHOT
                && !Boolean.TRUE.equals(plan.request().parameters().get("force"))) {
            LocalDate lastRefresh = latestVerifiedSnapshotDate(plan.targetId());
            if (lastRefresh != null && plan.request().logicalDate().isBefore(lastRefresh.plusDays(7)))
                throw new IllegalStateException("D021 refresh became not due after planning; make a fresh plan");
        }
        return SyncRunExecution.execute(ledgerPath, runId, priorRunId, plan.targetId(), plan.request(),
                "Cannot read D021 cancellation state", () -> {
            var writer = this.target.newWriter(plan.targetId());
            Path runEvidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId).toAbsolutePath().normalize();
            return new IndexWeightSyncAdapter(new IndexWeightSource(pages, new IndexWeightMapper(), runEvidence.resolve("source"), nameResolver),
                    writer, runEvidence);
        });
    }

    private void validatePlan(Plan plan) {
        if (!plan.request().definition().equals(IndexWeightSyncJobOwner.DEFINITION)
                || !plan.targetId().equals(plan.request().parameters().get("targetId"))
                || !plan.targetId().equals(targetId())
                || !nameResolver.targetId().equals(plan.request().parameters().get("stockDetailTargetId")))
            throw new IllegalStateException("Frozen D021 plan or isolated target identity changed");
    }

    private void validateBaseline(Plan plan) {
        var current = this.target.newWriter(plan.targetId()).readExistingRange();
        if (!Objects.equals(current.min(), plan.targetMinBefore()) || !Objects.equals(current.max(), plan.targetMaxBefore()))
            throw new IllegalStateException("D021 physical target date range changed after plan; make a fresh plan");
    }

    private Path evidenceRoot(String runId) {
        return ledgerPath.getParent().resolve("sync-evidence").resolve(runId).toAbsolutePath().normalize();
    }

    /** Only successful complete snapshots of this physical target count as refreshes; backfill never advances this gate. */
    private LocalDate latestVerifiedSnapshotDate(String target) throws Exception {
        if (!java.nio.file.Files.isRegularFile(ledgerPath)) return null;
        if (!SqliteLedgerSchema.hasRunHistoryTable(ledgerPath)) return null;
        return IndexWeightCoverage.latestVerifiedSnapshot(ledgerPath, target, nameResolver.targetId())
                .map(IndexWeightCoverage.Snapshot::verifiedLocalDate).orElse(null);
    }
}
