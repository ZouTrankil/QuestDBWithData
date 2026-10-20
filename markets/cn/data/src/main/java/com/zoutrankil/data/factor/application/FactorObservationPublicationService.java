package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.factor.storage.GlobalObservationWriteSession;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.VerifiedSliceRecovery;

import io.questdb.client.QuestDB;

import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/** Guarded in-process observation publication shared by CLI and orchestration. */
public final class FactorObservationPublicationService {
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final Path ledgerPath;

    public FactorObservationPublicationService(
            JdbcTemplate jdbc, QuestDB questdb, Path ledgerPath) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb);
        this.ledgerPath = Objects.requireNonNull(ledgerPath);
    }

    /** Read-only admission before a pipeline creates a new resume receipt. Execution rechecks it. */
    public void requireResumableEvidence(Path evidence, LocalDate logicalDate, String priorRunId)
            throws Exception {
        var frozen = FactorObservationEvidence.read(evidence);
        var ledger = new SyncRunLedger(ledgerPath);
        var prior = Objects.requireNonNull(ledger.getRun(priorRunId), "Unknown prior factor run");
        int version =
                JobDefinitionJson.mapper()
                        .readTree(prior.frozenJson())
                        .path("definition")
                        .path("version")
                        .asInt(-1);
        var session = new GlobalObservationWriteSession(jdbc, questdb);
        String target = session.targetIdentity();
        var from =
                frozen.rows().stream()
                        .map(row -> row.signalDate())
                        .min(LocalDate::compareTo)
                        .orElseThrow();
        var through =
                frozen.rows().stream()
                        .map(row -> row.signalDate())
                        .max(LocalDate::compareTo)
                        .orElseThrow();
        var request =
                FactorObservationJobService.definition(version)
                        .freeze(
                                SyncJobDefinition.Mode.INCREMENTAL,
                                Map.of(
                                        "targetId",
                                        target,
                                        "factorEvidenceSha256",
                                        frozen.fileSha256()),
                                from,
                                through,
                                logicalDate);
        VerifiedSliceRecovery.load(ledger, priorRunId, request, target);
    }

    /** Shared finite publication entry; caller retains runId before starting the writer. */
    public SyncJobRunner.Result publishEvidence(
            Path evidence,
            LocalDate logicalDate,
            SyncJobDefinition.Mode mode,
            String priorRunId,
            String runId)
            throws Exception {
        evidence = evidence.toAbsolutePath().normalize();
        var snapshot = FactorObservationEvidence.read(evidence);
        LocalDate from =
                snapshot.rows().stream()
                        .map(row -> row.signalDate())
                        .min(LocalDate::compareTo)
                        .orElseThrow();
        LocalDate through =
                snapshot.rows().stream()
                        .map(row -> row.signalDate())
                        .max(LocalDate::compareTo)
                        .orElseThrow();
        var session = new GlobalObservationWriteSession(jdbc, questdb);
        var ledger = new SyncRunLedger(ledgerPath);
        var service =
                new FactorObservationJobService(
                        new SyncJobRunner<>(ledger, new DatasetIntervalLock(ledgerPath)));
        var request =
                service.plan(
                        mode,
                        from,
                        through,
                        logicalDate,
                        session.targetIdentity(),
                        snapshot.fileSha256());
        if (priorRunId != null) {
            var prior =
                    Objects.requireNonNull(ledger.getRun(priorRunId), "Unknown prior factor run");
            int version =
                    JobDefinitionJson.mapper()
                            .readTree(prior.frozenJson())
                            .path("definition")
                            .path("version")
                            .asInt(-1);
            request =
                    FactorObservationJobService.definition(version)
                            .freeze(
                                    mode,
                                    Map.of(
                                            "targetId",
                                            session.targetIdentity(),
                                            "factorEvidenceSha256",
                                            snapshot.fileSha256()),
                                    from,
                                    through,
                                    logicalDate);
        }
        long started = System.nanoTime();
        // Persist the process/run binding before opening the writer. A hard process exit
        // can prevent the terminal result and finally block from reaching the log.
        var writerIdentity =
                Map.of(
                        "schema",
                        "java.factor_writer_identity.v1",
                        "runId",
                        runId,
                        "pid",
                        ProcessHandle.current().pid(),
                        "evidenceSha256",
                        snapshot.fileSha256(),
                        "evidence",
                        evidence.toString(),
                        "targetId",
                        session.targetIdentity(),
                        "startedAt",
                        Instant.now().toString());
        Path writerProof =
                ledgerPath
                        .toAbsolutePath()
                        .normalize()
                        .getParent()
                        .resolve("factor-writers")
                        .resolve(runId + ".json");
        Files.createDirectories(writerProof.getParent());
        FileEvidenceStore.writeNewDurable(
                writerProof, JobDefinitionJson.mapper().writeValueAsBytes(writerIdentity));
        try (var ledgerSession = ledger.keepOpen()) {
            return service.execute(
                    runId,
                    priorRunId,
                    request,
                    snapshot.rows(),
                    snapshot.sourceComplete(),
                    evidence,
                    session,
                    () -> Thread.currentThread().isInterrupted());
        } finally {
            LoggerFactory.getLogger(FactorObservationPublicationService.class)
                    .info(
                            "Factor publication timing run={} elapsedNanos={} stages={}",
                            runId,
                            System.nanoTime() - started,
                            session.timings());
        }
    }
}
