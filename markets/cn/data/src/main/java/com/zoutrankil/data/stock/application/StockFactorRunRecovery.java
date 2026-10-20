package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.stock.feature.StockFactor;
import com.zoutrankil.data.domain.stock.feature.StockFactorKey;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.stock.port.StockFactorTarget;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Complete a frozen one-day page under its retained lock, then allow ordinary run revalidation. */
public final class StockFactorRunRecovery {
    private StockFactorRunRecovery() {}

    public static Map<String, Object> finish(
            Path ledgerPath, String runId, Path writerLog, StockFactorTarget target)
            throws Exception {
        if (!runId.matches("stk-factor-[0-9a-f-]{36}") || !target.tableName().equals("stk_factor"))
            throw new IllegalArgumentException("Stock factor run and formal target required");
        var json = JobDefinitionJson.mapper();
        byte[] logBytes =
                FileEvidenceStore.readBounded(
                        writerLog,
                        4 * 1024 * 1024,
                        () -> new IllegalArgumentException("Writer log too large"));
        String log = new String(logBytes, StandardCharsets.UTF_8);
        var pidMatcher =
                Pattern.compile(
                                "Starting QuestDataApplication using Java[^\\r\\n]* with PID ([0-9]+)")
                        .matcher(log);
        if (!pidMatcher.find()) throw new IllegalArgumentException("Original writer PID missing");
        long writerPid = Long.parseLong(pidMatcher.group(1));
        if (pidMatcher.find()
                || !log.contains("stk_factor sync incomplete: IN_DOUBT; run=" + runId)
                || !log.contains("finished with non-zero exit value"))
            throw new IllegalArgumentException(
                    "One matching terminated writer invocation required");
        stopped(writerPid);
        var ledger = new SyncRunLedger(ledgerPath);
        var run = ledger.getRun(runId);
        var frozen = json.readTree(run.frozenJson());
        var definition = StockFactorSyncJobOwner.DEFINITION;
        if (!run.jobId().equals(definition.jobId())
                || run.jobVersion() != definition.version()
                || !json.treeToValue(frozen.path("definition"), SyncJobDefinition.class)
                        .equals(definition)
                || !run.targetId().equals(target.targetId())
                || !run.targetId().equals(frozen.path("parameters").path("targetId").asText()))
            throw new IllegalStateException("Frozen definition or physical target differs");
        var date = LocalDate.parse(frozen.path("from").asText());
        if (!date.toString().equals(frozen.path("to").asText())
                || frozen.path("parameters").hasNonNull("tsCode"))
            throw new IllegalArgumentException("Recovery currently requires one full-market day");
        var locks = new DatasetIntervalLock(ledgerPath);
        var scope = new DatasetIntervalLock.Scope("stk_factor", date, date);
        var lease = locks.findOwned(runId, scope);
        if (lease == null || !lease.inDoubt())
            throw new IllegalStateException("Retained uncertain date lock required");
        Path root =
                ledgerPath
                        .toAbsolutePath()
                        .getParent()
                        .resolve("sync-evidence")
                        .resolve(runId)
                        .toRealPath();
        Path recovery = root.resolve("stock-factor-recovery");
        Files.createDirectories(recovery);
        if (Files.isSymbolicLink(recovery) || !recovery.toRealPath().startsWith(root))
            throw new IllegalStateException("Invalid recovery directory");
        try (var channel =
                        FileChannel.open(
                                recovery.resolve("operation.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var operation = channel.tryLock()) {
            if (operation == null) throw new IllegalStateException("Recovery already active");
            try (var prior = Files.newDirectoryStream(recovery, "*.intent.json")) {
                for (var file : prior) {
                    var intent =
                            json.readTree(
                                    FileEvidenceStore.readBounded(
                                            file,
                                            1024 * 1024,
                                            () ->
                                                    new IllegalStateException(
                                                            "Oversized recovery intent")));
                    stopped(intent.path("pid").asLong(-1));
                    if (!runId.equals(intent.path("runId").asText()))
                        throw new IllegalStateException("Foreign recovery intent");
                }
            }
            var entries = ledger.entries(runId, null, 4);
            if (entries.size() != 3
                    || entries.stream().anyMatch(e -> e.state() != SyncRunState.IN_DOUBT))
                throw new IllegalStateException(
                        "One untouched uncertain attempt and slice required");
            var slice =
                    entries.stream()
                            .filter(e -> e.kind() == SyncRunLedger.Kind.SLICE)
                            .findFirst()
                            .orElseThrow();
            var events = ledger.events(slice.id(), -1, 100);
            var fetched = events.stream().filter(e -> e.state() == SyncRunState.FETCHED).toList();
            if (events.size() == 100
                    || fetched.size() != 1
                    || events.stream().noneMatch(e -> e.state() == SyncRunState.SUBMITTED))
                throw new IllegalStateException("Durable fetched and submitted page required");
            var fetch = json.readTree(fetched.getFirst().payloadJson());
            Path source = Path.of(fetch.path("responseEvidence").asText()).toRealPath();
            if (!source.startsWith(root.resolve("source")))
                throw new IllegalStateException("Source receipt outside run");
            String sha = fetch.path("sourceFingerprint").asText();
            var snapshot =
                    StockFactorFrozenEvidence.read(
                            source, sha, new StockFactorSource.Query(date, date, null));
            if (fetch.path("returnedRows").asInt(-1) != snapshot.rows().size())
                throw new IllegalStateException("Fetched page count differs");
            var receipts = json.readTree(slice.payloadJson()).path("writeResult");
            var port = target.newWriter(run.targetId());
            var codec = port.codec();
            var inspection =
                    StockFactorSubmittedPrefix.inspect(snapshot.rows(), receipts, codec, port);
            String attempt = UUID.randomUUID().toString();
            Path intent = recovery.resolve(attempt + ".intent.json");
            stopped(writerPid);
            FileEvidenceStore.writeNewDurable(
                    intent,
                    json.writeValueAsBytes(
                            Map.of(
                                    "runId",
                                    runId,
                                    "pid",
                                    ProcessHandle.current().pid(),
                                    "targetId",
                                    run.targetId(),
                                    "sourceSha256",
                                    sha,
                                    "missingKeys",
                                    inspection.missingRows().stream()
                                            .map(StockFactor::key)
                                            .toList(),
                                    "writerLogSha256",
                                    FileEvidenceStore.sha256(logBytes))));
            if (!inspection.missingRows().isEmpty()) {
                long started = System.nanoTime();
                var guarded =
                        new VerifiedBatchExecutor.Port<StockFactor, StockFactorKey>() {
                            public void preflight() throws Exception {
                                if (System.nanoTime() - started >= Duration.ofMinutes(10).toNanos())
                                    throw new IllegalStateException(
                                            "Recovery write deadline reached; lock retained");
                                stopped(writerPid);
                                var current = ledger.get(slice.id());
                                var owned = locks.findOwned(runId, scope);
                                if (current.revision() != slice.revision()
                                        || current.state() != SyncRunState.IN_DOUBT
                                        || owned == null
                                        || !owned.inDoubt())
                                    throw new IllegalStateException(
                                            "Recovery submission authority changed");
                                StockFactorFrozenEvidence.read(
                                        source, sha, new StockFactorSource.Query(date, date, null));
                                port.preflight();
                            }

                            public void send(List<StockFactor> rows) throws Exception {
                                preflight();
                                port.send(rows);
                            }

                            public List<StockFactor> readback(List<StockFactorKey> keys)
                                    throws Exception {
                                return port.readback(keys);
                            }

                            public boolean walSettled() throws Exception {
                                return port.walSettled();
                            }

                            public boolean uncertainSenderStopped() throws Exception {
                                return port.uncertainSenderStopped();
                            }
                        };
                var executor =
                        new VerifiedBatchExecutor<>(
                                new VerifiedBatchExecutor.Policy(
                                        250,
                                        1024 * 1024,
                                        40,
                                        Duration.ofMinutes(3),
                                        Duration.ofMillis(100)),
                                codec,
                                guarded);
                // The original durable SUBMITTED page and retained interval still own these exact
                // keys.
                port.submissionRecorded(
                        new VerifiedBatchExecutor.Submission(
                                ledgerPath,
                                runId,
                                slice.id(),
                                events.stream()
                                        .filter(e -> e.state() == SyncRunState.SUBMITTED)
                                        .findFirst()
                                        .orElseThrow()
                                        .revision(),
                                sha));
                var written = executor.execute(inspection.missingRows().iterator());
                FileEvidenceStore.writeNewDurable(
                        recovery.resolve(attempt + ".write.json"), json.writeValueAsBytes(written));
                if (written.status() != VerifiedBatchExecutor.Status.VERIFIED)
                    throw new IllegalStateException(
                            "Recovery write remains uncertain; retained date lock preserved");
            }
            var complete =
                    StockFactorSubmittedPrefix.inspect(snapshot.rows(), receipts, codec, port);
            if (!complete.missingRows().isEmpty() || !run.targetId().equals(target.targetId()))
                throw new IllegalStateException("Complete frozen page readback failed");
            stopped(writerPid);
            StockFactorFrozenEvidence.read(
                    source, sha, new StockFactorSource.Query(date, date, null));
            Path proof = recovery.resolve(attempt + ".verified.json");
            int n = snapshot.rows().size();
            var verification =
                    Map.of(
                            "passed",
                            true,
                            "writerStopped",
                            true,
                            "expectedRows",
                            n,
                            "actualRows",
                            n,
                            "matchedRows",
                            n,
                            "missingKeys",
                            0,
                            "duplicateKeys",
                            0,
                            "mismatchedRows",
                            0,
                            "sourceFingerprint",
                            sha,
                            "readbackEvidence",
                            proof.toString());
            FileEvidenceStore.writeNewDurable(
                    proof,
                    json.writeValueAsBytes(
                            Map.of(
                                    "runId",
                                    runId,
                                    "targetId",
                                    run.targetId(),
                                    "verification",
                                    verification,
                                    "reusedRows",
                                    inspection.matchingRows(),
                                    "writtenRows",
                                    inspection.missingRows().size())));
            ledger.transition(
                    slice.id(),
                    slice.revision(),
                    SyncRunState.VERIFIED,
                    json.writeValueAsString(Map.of("verification", verification)));
            var owners =
                    new ArrayList<>(
                            entries.stream()
                                    .filter(e -> e.kind() != SyncRunLedger.Kind.SLICE)
                                    .toList());
            owners.sort(Comparator.comparing(e -> e.kind() == SyncRunLedger.Kind.RUN ? 1 : 0));
            for (var owner : owners)
                ledger.transition(
                        owner.id(),
                        owner.revision(),
                        SyncRunState.PARTIAL,
                        json.writeValueAsString(
                                Map.of(
                                        "submittedRows",
                                        n,
                                        "sourceComplete",
                                        false,
                                        "allSubmittedSlicesVerified",
                                        true,
                                        "verification",
                                        verification)));
            locks.releaseAfterReconciliation(lease, true, true);
            return Map.of(
                    "runId",
                    runId,
                    "state",
                    "PARTIAL",
                    "verifiedRows",
                    n,
                    "writtenRows",
                    inspection.missingRows().size(),
                    "proof",
                    proof.toString());
        }
    }

    private static void stopped(long pid) {
        if (pid < 1 || ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
            throw new IllegalStateException("Original or prior recovery writer is still alive");
    }
}
