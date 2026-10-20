package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.derived.table.FactorDailyMetricRow;
import com.zoutrankil.data.domain.temporal.MarketId;
import com.zoutrankil.data.domain.temporal.MarketTime;
import com.zoutrankil.data.factor.storage.DailyMetricPublicationPreparation;
import com.zoutrankil.data.factor.storage.DailyMetricWriteSession;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.market.cn.time.CnMarketTime;

import io.questdb.client.QuestDB;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** In-process guarded publication shared by CLI and post-close orchestration. */
public final class DailyMetricPublicationService {
    private final MarketTime marketTime;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final Path ledgerPath;

    public DailyMetricPublicationService(JdbcTemplate jdbc, QuestDB questdb, Path ledgerPath) {
        this(jdbc, questdb, ledgerPath, CnMarketTime.system());
    }

    public DailyMetricPublicationService(
            JdbcTemplate jdbc, QuestDB questdb, Path ledgerPath, MarketTime marketTime) {
        this.marketTime = Objects.requireNonNull(marketTime).requireMarket(MarketId.CN);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb);
        this.ledgerPath = Objects.requireNonNull(ledgerPath);
    }

    /** Typed composition entry; business PARTIAL is returned without parsing console output. */
    public SyncJobRunner.Result publishComputation(
            Path computation, Path evidenceRoot, LocalDate logical, String runId, String prior)
            throws Exception {
        if (logical == null || logical.isAfter(marketTime.today()))
            throw new IllegalArgumentException("Current or historical logical date required");
        for (String id : List.of(runId, prior == null ? runId : prior))
            if (!id.matches(
                    "factor-daily-metric-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
                throw new IllegalArgumentException("Canonical daily metric run identity required");
        return publishPrepared(
                DailyMetricPublicationPreparation.prepare(jdbc, computation, evidenceRoot),
                logical,
                runId,
                prior);
    }

    private SyncJobRunner.Result publishPrepared(
            DailyMetricPublicationPreparation.Prepared prepared,
            LocalDate logical,
            String runId,
            String prior)
            throws Exception {
        String target = DailyMetricWriteSession.targetIdentity(jdbc);
        var session = new DailyMetricWriteSession(jdbc, questdb, target);
        var ledger = new SyncRunLedger(ledgerPath);
        var writer = new LinkedHashMap<String, Object>();
        writer.put("schema", "java.daily_metric_writer_identity.v1");
        writer.put("runId", runId);
        writer.put("priorRunId", prior);
        writer.put("pid", ProcessHandle.current().pid());
        writer.put("startedAt", Instant.now());
        writer.put("targetId", target);
        writer.put("evidence", prepared.evidence().toString());
        writer.put("inputFingerprint", prepared.guard().fingerprint());
        Path proof =
                ledgerPath
                        .toAbsolutePath()
                        .normalize()
                        .getParent()
                        .resolve("factor-writers")
                        .resolve(runId + ".json");
        Files.createDirectories(proof.getParent());
        FileEvidenceStore.writeNewDurable(
                proof, JobDefinitionJson.mapper().writeValueAsBytes(writer));
        try (var lease = ledger.keepOpen()) {
            var runner =
                    new SyncJobRunner<FactorDailyMetricRow, DailyMetricDelta.Key>(
                            ledger, new DatasetIntervalLock(ledgerPath));
            var result =
                    DailyMetricJobService.execute(
                            runner,
                            session,
                            target,
                            runId,
                            prior,
                            prepared.evidence(),
                            logical,
                            prepared.guard(),
                            () -> Thread.currentThread().isInterrupted());
            FileEvidenceStore.writeNewDurable(
                    proof.resolveSibling(runId + ".result.json"),
                    JobDefinitionJson.mapper().writeValueAsBytes(result));
            return result;
        }
    }
}
