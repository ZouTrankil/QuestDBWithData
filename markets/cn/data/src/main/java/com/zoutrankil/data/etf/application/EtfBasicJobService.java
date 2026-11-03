package com.zoutrankil.data.etf.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.fund.EtfBasic;
import com.zoutrankil.data.domain.fund.EtfBasicDataset;
import com.zoutrankil.data.domain.fund.EtfBasicKey;
import com.zoutrankil.data.etf.port.EtfBasicDirectorySession;
import com.zoutrankil.data.etf.port.EtfWriteTarget;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.FileEvidenceStore.Ownership;
import com.zoutrankil.data.repository.FileEvidenceStore.Symlinks;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.FrozenRunRequest;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.SyncRunExecution;
import com.zoutrankil.data.service.TusharePageService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Explicit plan/run/resume/status/cancel API for the isolated D013 full-directory snapshot. */
@Service
public final class EtfBasicJobService {
    public record Plan(FrozenRequest request, String targetId, Instant observedAt) {
        public Plan {
            Objects.requireNonNull(request);
            Objects.requireNonNull(targetId);
            Objects.requireNonNull(observedAt);
            EtfBasicSyncAdapter.validateRequest(request);
            if (!targetId.equals(request.parameters().get("targetId"))
                    || !observedAt.equals(EtfBasicSyncAdapter.observedAt(request)))
                throw new IllegalArgumentException(
                        "etf_basic target and observation must be frozen in the plan");
        }
    }

    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final EtfWriteTarget<EtfBasic, EtfBasicKey> target;
    private final Path ledgerPath;
    private final String table;

    @Autowired
    public EtfBasicJobService(
            @Lazy SyncJobRegistry jobs,
            TusharePageService pages,
            EtfWriteTarget<EtfBasic, EtfBasicKey> target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(jobs, pages, target, Path.of(ledgerPath));
    }

    public EtfBasicJobService(
            SyncJobRegistry jobs,
            TusharePageService pages,
            EtfWriteTarget<EtfBasic, EtfBasicKey> target,
            Path ledgerPath) {
        this.jobs = Objects.requireNonNull(jobs);
        this.pages = Objects.requireNonNull(pages);
        this.target = Objects.requireNonNull(target);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
        String table = target.tableName();
        EtfBasicDataset.requireExecutionTable(table);
        this.table = table;
    }

    public String tableName() {
        return table;
    }

    public Plan plan(LocalDate logicalDate) throws Exception {
        Objects.requireNonNull(logicalDate, "Frozen etf_basic logical date required");
        String target = targetId();
        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var request =
                jobs.prepare(
                        EtfBasicSyncJobOwner.DEFINITION.jobId(),
                        EtfBasicSyncJobOwner.DEFINITION.version(),
                        Mode.SNAPSHOT,
                        Map.of("targetId", target, "observedAt", observedAt.toString()),
                        null,
                        null,
                        logicalDate);
        new EtfBasicSyncAdapter(
                        pages,
                        this.target.newWriter(target),
                        ledgerPath
                                .getParent()
                                .resolve("sync-evidence")
                                .resolve("etf-basic-preflight"),
                        table.equals("etf_basic"))
                .preflight(request);
        requireSameTarget(target, targetId());
        return new Plan(request, target, observedAt);
    }

    /** Discover and bind exactly the configured isolated YEAR/WAL/DEDUP target. */
    public String targetId() {
        EtfBasicDataset.requireExecutionTable(table);
        return target.targetId();
    }

    /** 查询当前完整目录并检查旧目标，不创建同步运行，不发送任何行情。 */
    public Map<String, Object> checkSource(LocalDate logicalDate) throws Exception {
        if (!LocalDate.now(ZoneId.of("Asia/Shanghai")).equals(logicalDate))
            throw new IllegalArgumentException("ETF目录核查仅支持当前观察日");
        String identity = targetId();
        var writer = target.newWriter(identity);
        writer.preflight();
        if (!(writer instanceof EtfBasicDirectorySession directory))
            throw new IllegalArgumentException("需要完整目录回读端口");
        var source =
                new EtfBasicSource(
                        pages,
                        ledgerPath
                                .getParent()
                                .resolve("sync-evidence")
                                .resolve("etf-basic-source-check-" + UUID.randomUUID()));
        var result =
                source.fetch(
                        Instant.now().truncatedTo(ChronoUnit.MICROS),
                        () -> Thread.currentThread().isInterrupted());
        var old = directory.readDirectory();
        var keys = new HashSet<EtfBasicKey>();
        result.rows().forEach(r -> keys.add(r.key()));
        long retained = old.stream().filter(r -> !keys.contains(r.key())).count();
        requireSameTarget(identity, targetId());
        return Map.of(
                "dataset",
                "etf_basic",
                "targetId",
                identity,
                "sourceRows",
                result.rows().size(),
                "oldRows",
                old.size(),
                "retainedLegacyKeys",
                retained,
                "sourceCoverageVerified",
                true,
                "targetDataVerified",
                false,
                "productionWritten",
                false,
                "sourceEvidence",
                result.receipt(),
                "sourceFingerprint",
                result.fingerprint());
    }

    /** 编排方固定原运行编号，恢复仍须走原账本协议。 */
    public SyncJobRunner.Result executeRecorded(String runId, Plan plan) throws Exception {
        validatePlan(plan);
        return execute(runId, null, plan);
    }

    /** 只读核对原全目录和来源原件；供明确收尾使用，不重新抓当前目录替换旧观察。 */
    public String revalidate(String runId) throws Exception {
        var restored =
                FrozenRunRequest.restoreCompleted(
                        ledgerPath, runId, EtfBasicSyncJobOwner.DEFINITION);
        requireSameTarget(restored.targetId(), targetId());
        Path root = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        var candidates = new ArrayList<Path>();
        try (var files = Files.newDirectoryStream(root, "complete-*.json")) {
            for (var file : files) candidates.add(file);
        }
        if (candidates.size() != 1) throw new IllegalStateException("原ETF目录完成凭证不唯一");
        Path receipt =
                FileEvidenceStore.owned(
                        candidates.getFirst(),
                        root,
                        Ownership.DIRECT_CHILD,
                        Symlinks.REJECT_LEAF,
                        () -> new IllegalStateException("原ETF目录凭证越界"));
        var proof =
                JobDefinitionJson.mapper()
                        .readTree(
                                FileEvidenceStore.readBounded(
                                        receipt,
                                        64 * 1024 * 1024,
                                        () -> new IllegalStateException("原ETF目录凭证超预算")));
        if (!proof.path("complete").asBoolean()
                || !proof.path("fullDirectoryVerified").asBoolean()
                || !EtfBasicSyncAdapter.observedAt(restored.request())
                        .toString()
                        .equals(proof.path("observedAt").asText()))
            throw new IllegalStateException("原ETF目录没有完整同观察时刻证明");
        Path raw =
                FileEvidenceStore.owned(
                        Path.of(proof.path("sourceReceipt").asText()),
                        root,
                        Ownership.DESCENDANT,
                        Symlinks.REJECT_LEAF,
                        () -> new IllegalStateException("原ETF来源越界"));
        byte[] bytes =
                FileEvidenceStore.readBounded(
                        raw, 64 * 1024 * 1024, () -> new IllegalStateException("原ETF来源超预算"));
        if (!FileEvidenceStore.sha256(bytes).equals(proof.path("sourceFingerprint").asText()))
            throw new IllegalStateException("原ETF目录来源摘要不符");
        var session = target.newWriter(restored.targetId());
        session.preflight();
        if (!(session instanceof EtfBasicDirectorySession directory))
            throw new IllegalStateException("ETF目录缺少全量读取端口");
        var values = new TreeMap<String, String>();
        for (var row : directory.readDirectory())
            if (values.putIfAbsent(
                            EtfBasicDirectoryEvidence.key(row),
                            EtfBasicDirectoryEvidence.value(session.codec(), row))
                    != null) throw new IllegalStateException("当前ETF目录业务键重复");
        if (!JobDefinitionJson.mapper()
                .valueToTree(values)
                .equals(proof.path("expectedFullDirectory")))
            throw new IllegalStateException("当前ETF完整键值与原凭证不符");
        requireSameTarget(restored.targetId(), targetId());
        return receipt.toString();
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception {
        validatePlan(plan);
        return execute("etf-basic-" + UUID.randomUUID(), null, plan);
    }

    public SyncJobRunner.Result resume(Plan plan, String priorRunId) throws Exception {
        validatePlan(plan);
        Objects.requireNonNull(priorRunId);
        var oldLedger = SyncRunLedger.openReadOnly(ledgerPath);
        var oldState = oldLedger.get(priorRunId).state();
        var oldRun = oldLedger.getRun(priorRunId);
        if (!Set.of(SyncRunState.FAILED, SyncRunState.CANCELLED, SyncRunState.PARTIAL)
                        .contains(oldState)
                || !oldRun.jobId().equals(plan.request().definition().jobId())
                || oldRun.jobVersion() != plan.request().definition().version()
                || !SyncRequestIdentity.fingerprint(oldRun.frozenJson(), plan.targetId())
                        .equals(SyncRequestIdentity.fingerprint(plan.request(), plan.targetId()))
                || !oldRun.targetId().equals(plan.targetId())
                || new DatasetIntervalLock(ledgerPath)
                                .findOwned(
                                        priorRunId, DatasetIntervalLock.Scope.allDates("etf_basic"))
                        != null)
            throw new IllegalStateException(
                    "Only a terminal failed/cancelled/partial exact-plan etf_basic run can resume");
        requireSameTarget(plan.targetId(), targetId());
        return execute("etf-basic-" + UUID.randomUUID(), priorRunId, plan);
    }

    /** Rebuilds a plan from the prior ledger's exact frozen request for CLI --resume-from. */
    public Plan restorePlan(String priorRunId) throws Exception {
        Objects.requireNonNull(priorRunId);
        var prior =
                FrozenRunRequest.restore(ledgerPath, priorRunId, EtfBasicSyncJobOwner.DEFINITION);
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

    public List<SyncRunLedger.Entry> entries(String runId, String afterId, int limit)
            throws Exception {
        return SyncRunLedger.openReadOnly(ledgerPath).entries(runId, afterId, limit);
    }

    public boolean cancel(String runId) throws Exception {
        return new SyncRunLedger(ledgerPath).requestCancellation(runId);
    }

    private SyncJobRunner.Result execute(String runId, String priorRunId, Plan plan)
            throws Exception {
        requireSameTarget(plan.targetId(), targetId());
        return SyncRunExecution.execute(
                ledgerPath,
                runId,
                priorRunId,
                plan.targetId(),
                plan.request(),
                "Cannot read etf_basic cancellation state",
                () -> {
                    var port = target.newWriter(plan.targetId());
                    var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
                    return new EtfBasicSyncAdapter(
                            pages, port, evidence, table.equals("etf_basic"));
                });
    }

    private void validatePlan(Plan plan) throws Exception {
        Objects.requireNonNull(plan);
        EtfBasicSyncAdapter.validateRequest(plan.request());
        requireSameTarget(plan.targetId(), plan.request().parameters().get("targetId").toString());
        requireSameTarget(plan.targetId(), targetId());
    }

    private static FrozenRequest restoreRequest(String frozenJson) throws Exception {
        var json = JobDefinitionJson.mapper();
        var saved = json.readTree(frozenJson);
        var definition = json.treeToValue(saved.path("definition"), SyncJobDefinition.class);
        if (!definition.equals(EtfBasicSyncJobOwner.DEFINITION))
            throw new IllegalArgumentException(
                    "Frozen etf_basic definition changed; cannot resume this run");
        Map<String, Object> parameters =
                json.convertValue(saved.path("parameters"), new TypeReference<>() {});
        var request =
                definition.freeze(
                        Mode.SNAPSHOT,
                        parameters,
                        null,
                        null,
                        LocalDate.parse(saved.path("logicalDate").asText()));
        if (!json.readTree(frozenJson)
                .equals(json.readTree(SyncRequestIdentity.snapshotJson(request))))
            throw new IllegalArgumentException(
                    "Frozen etf_basic request cannot be reconstructed exactly");
        EtfBasicSyncAdapter.validateRequest(request);
        return request;
    }

    private static void requireSameTarget(String expected, String actual) {
        if (expected == null || actual == null || !expected.equals(actual))
            throw new IllegalStateException(
                    "etf_basic physical target identity differs from frozen plan");
    }
}
