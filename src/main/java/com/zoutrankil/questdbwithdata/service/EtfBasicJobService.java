package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;
import com.zoutrankil.questdbwithdata.repository.*;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Explicit plan/run/resume/status/cancel API for the isolated D013 full-directory snapshot. */
@Service
public final class EtfBasicJobService {
    public record Plan(FrozenRequest request, String targetId, Instant observedAt) {
        public Plan {
            Objects.requireNonNull(request); Objects.requireNonNull(targetId); Objects.requireNonNull(observedAt);
            EtfBasicSyncAdapter.validateRequest(request);
            if (!targetId.equals(request.parameters().get("targetId"))
                    || !observedAt.equals(EtfBasicSyncAdapter.observedAt(request)))
                throw new IllegalArgumentException("etf_basic target and observation must be frozen in the plan");
        }
    }

    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final Path ledgerPath;
    private final String table;

    @Autowired
    public EtfBasicJobService(@Lazy SyncJobRegistry jobs, TusharePageService pages,
            JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            @Value("${app.sync.etf-basic-table:java_d013_etf_basic_acceptance}") String table) {
        this(jobs, pages, jdbc, questdb, Path.of(ledgerPath), table);
    }

    public EtfBasicJobService(SyncJobRegistry jobs, TusharePageService pages, JdbcTemplate jdbc,
            QuestDB questdb, Path ledgerPath, String table) {
        this.jobs = Objects.requireNonNull(jobs); this.pages = Objects.requireNonNull(pages);
        this.jdbc = Objects.requireNonNull(jdbc); this.questdb = Objects.requireNonNull(questdb);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
        EtfBasicDataset.requireIsolatedTable(table); this.table = table;
    }

    public String tableName() { return table; }

    public Plan plan(java.time.LocalDate logicalDate) throws Exception {
        Objects.requireNonNull(logicalDate, "Frozen etf_basic logical date required");
        String target = targetId();
        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var request = jobs.prepare(EtfBasicSyncJobOwner.DEFINITION.jobId(),
                EtfBasicSyncJobOwner.DEFINITION.version(), Mode.SNAPSHOT,
                Map.of("targetId", target, "observedAt", observedAt.toString()), null, null, logicalDate);
        new EtfBasicSyncAdapter(pages, new EtfBasicWritePort(table, target, jdbc, questdb),
                ledgerPath.getParent().resolve("sync-evidence").resolve("etf-basic-preflight")).preflight(request);
        requireSameTarget(target, targetId());
        return new Plan(request, target, observedAt);
    }

    /** Discover and bind exactly the configured isolated YEAR/WAL/DEDUP target. */
    public String targetId() {
        EtfBasicDataset.requireIsolatedTable(table);
        QuestDbWriteChecks.preflight(jdbc, table, EtfBasicDataset.definition(table));
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact isolated etf_basic QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception {
        validatePlan(plan); return execute("etf-basic-" + UUID.randomUUID(), null, plan);
    }

    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        validatePlan(plan); Objects.requireNonNull(priorRunId);
        var oldLedger = SyncRunLedger.openReadOnly(ledgerPath);
        var oldState = oldLedger.get(priorRunId).state(); var oldRun = oldLedger.getRun(priorRunId);
        if (!Set.of(SyncRunState.FAILED, SyncRunState.CANCELLED, SyncRunState.PARTIAL).contains(oldState)
                || !oldRun.jobId().equals(plan.request().definition().jobId())
                || oldRun.jobVersion() != plan.request().definition().version()
                || !SyncRequestIdentity.fingerprint(oldRun.frozenJson(), plan.targetId())
                        .equals(SyncRequestIdentity.fingerprint(plan.request(), plan.targetId()))
                || !oldRun.targetId().equals(plan.targetId())
                || new DatasetIntervalLock(ledgerPath).findOwned(priorRunId,
                        DatasetIntervalLock.Scope.allDates("etf_basic")) != null)
            throw new IllegalStateException("Only a terminal failed/cancelled/partial exact-plan etf_basic run can resume");
        requireSameTarget(plan.targetId(), targetId());
        return execute("etf-basic-" + UUID.randomUUID(), priorRunId, plan);
    }

    /** Rebuilds a plan from the prior ledger's exact frozen request for CLI --resume-from. */
    public Plan restorePlan(String priorRunId) throws Exception {
        Objects.requireNonNull(priorRunId);
        var prior = FrozenRunRequest.restore(ledgerPath, priorRunId, EtfBasicSyncJobOwner.DEFINITION);
        var request = prior.request();
        String target = prior.targetId();
        requireSameTarget(target, request.parameters().get("targetId").toString());
        return new Plan(request, target, EtfBasicSyncAdapter.observedAt(request));
    }

    public SyncJobRunner.Result resume(String priorRunId) throws Exception {
        return resume(restorePlan(priorRunId), priorRunId);
    }

    public SyncRunLedger.Entry status(String runId) throws Exception {
        return SyncRunLedger.openReadOnly(ledgerPath).get(runId);
    }
    public List<SyncRunLedger.Entry> entries(String runId, String afterId, int limit) throws Exception {
        return SyncRunLedger.openReadOnly(ledgerPath).entries(runId, afterId, limit);
    }
    public boolean cancel(String runId) throws Exception { return new SyncRunLedger(ledgerPath).requestCancellation(runId); }

    private SyncJobRunner.Result execute(String runId, String priorRunId, Plan plan) throws Exception {
        requireSameTarget(plan.targetId(), targetId());
        var ledger = new SyncRunLedger(ledgerPath);
        var port = new EtfBasicWritePort(table, plan.targetId(), jdbc, questdb);
        var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        var adapter = new EtfBasicSyncAdapter(pages, port, evidence);
        var runner = new SyncJobRunner<EtfBasic, EtfBasicKey>(ledger, new DatasetIntervalLock(ledgerPath));
        java.util.function.BooleanSupplier cancelled = () -> {
            if (Thread.currentThread().isInterrupted()) return true;
            try { return ledger.cancellationRequested(runId); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot read etf_basic cancellation state", failure); }
        };
        return priorRunId == null
                ? runner.run(runId, null, plan.targetId(), plan.request(), adapter, cancelled)
                : runner.resume(runId, priorRunId, plan.targetId(), plan.request(), adapter, cancelled);
    }

    private void validatePlan(Plan plan) throws Exception {
        Objects.requireNonNull(plan);
        EtfBasicSyncAdapter.validateRequest(plan.request());
        requireSameTarget(plan.targetId(), plan.request().parameters().get("targetId").toString());
        requireSameTarget(plan.targetId(), targetId());
    }

    private static FrozenRequest restoreRequest(String frozenJson) throws Exception {
        var json = JobDefinitionJson.mapper(); var saved = json.readTree(frozenJson);
        var definition = json.treeToValue(saved.path("definition"), SyncJobDefinition.class);
        if (!definition.equals(EtfBasicSyncJobOwner.DEFINITION))
            throw new IllegalArgumentException("Frozen etf_basic definition changed; cannot resume this run");
        Map<String, Object> parameters = json.convertValue(saved.path("parameters"),
                new com.fasterxml.jackson.core.type.TypeReference<>() {});
        var request = definition.freeze(Mode.SNAPSHOT, parameters, null, null,
                java.time.LocalDate.parse(saved.path("logicalDate").asText()));
        if (!json.readTree(frozenJson).equals(json.readTree(SyncRequestIdentity.snapshotJson(request))))
            throw new IllegalArgumentException("Frozen etf_basic request cannot be reconstructed exactly");
        EtfBasicSyncAdapter.validateRequest(request);
        return request;
    }

    private static void requireSameTarget(String expected, String actual) {
        if (expected == null || actual == null || !expected.equals(actual))
            throw new IllegalStateException("etf_basic physical target identity differs from frozen plan");
    }
}
