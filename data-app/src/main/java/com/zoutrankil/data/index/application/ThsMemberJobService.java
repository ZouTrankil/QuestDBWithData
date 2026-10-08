package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.port.ThsMemberTarget;

import com.zoutrankil.data.index.domain.ThsMemberState;


import com.zoutrankil.data.index.mapper.ThsMemberMapper;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Registered manual one-board THS membership refresh with bounded source and full-value verification. */
@Service
public final class ThsMemberJobService implements SyncJobOwner {
    public record Result(String runId, SyncRunState state, String board, int sourceRows,
                         int beforeBoardRows, int verifiedBoardRows, long copiedOtherRows,
                         String publicationId, String evidence, String errorCode) {}
    private final ThsMemberTarget targetAccess;
    private final TusharePageService pages;
    private final Path ledgerPath;
    private final String table;

    @org.springframework.beans.factory.annotation.Autowired
    public ThsMemberJobService(ThsMemberTarget targetAccess,TusharePageService pages,
            @org.springframework.beans.factory.annotation.Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(targetAccess,pages,Path.of(ledgerPath));
    }
    public ThsMemberJobService(ThsMemberTarget targetAccess,TusharePageService pages,Path ledgerPath) {
        this.targetAccess=Objects.requireNonNull(targetAccess);this.pages=Objects.requireNonNull(pages);
        this.ledgerPath=ledgerPath.toAbsolutePath().normalize();
        DatasetDefinition.identifier(targetAccess.tableName());this.table=targetAccess.tableName();
    }
    public static SyncJobDefinition definition() {
        return new SyncJobDefinition("data.ths_member", 1, "ths_member", 1, "ths_member_owner",
                Set.of(Mode.INCREMENTAL), Mode.INCREMENTAL,
                Map.of("board_code", new Parameter(ParameterType.STRING, true, 12, 1, Set.of())),
                "tushare.shared", "ths_member.board", "questdb.full_key_values",
                new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)),
                Duration.ofMinutes(30), new Budget(1, 1, 1, 1000000, 16 * 1024 * 1024),
                0, List.of(), Frequency.MONTHLY, ZoneId.of("Asia/Shanghai"), true, false);
    }
    @Override public String datasetId() { return "ths_member"; }
    @Override public Set<Mode> supportedSyncModes() { return definition().supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(definition()); }

    public FrozenRequest plan(String board, LocalDate logicalDate) {
        if (!ThsIndex.validCode(board)) throw new IllegalArgumentException("Exact THS board required");
        return definition().freeze(null, Map.of("board_code", board), logicalDate, logicalDate, logicalDate);
    }
    private static String validate(FrozenRequest request) {
        if (!request.definition().equals(definition()) || request.mode() != Mode.INCREMENTAL
                || request.from() == null || !request.from().equals(request.to())
                || !request.from().equals(request.logicalDate())
                || !(request.parameters().get("board_code") instanceof String board)
                || request.parameters().size() != 1 || !ThsIndex.validCode(board))
            throw new IllegalArgumentException("Frozen one-board THS member request required");
        return (String) request.parameters().get("board_code");
    }
    private record PreparedInput(List<ThsMember> rows, Path receipt, String fingerprint) {
        private PreparedInput { rows = List.copyOf(rows); }
    }
    private static String validatePrepared(FrozenRequest request) {
        if (!request.definition().jobId().equals("write.ths_member") || request.definition().version() != 1
                || !request.definition().datasetId().equals("ths_member")
                || request.definition().datasetVersion() != ThsMemberDataset.DEFINITION.schemaVersion()
                || request.mode() != Mode.INGEST || request.from() != null || request.to() != null
                || !(request.parameters().get("board_code") instanceof String board)
                || !ThsIndex.validCode(board)
                || !(request.parameters().get("payloadFingerprint") instanceof String hash)
                || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Prepared one-board THS member request required");
        return board;
    }
    public String targetId() {
        var identity = targetAccess.open(table).preflight();
        return targetAccess.identify( table, identity.id(), identity.directory());
    }
    public Result run(FrozenRequest request) throws Exception {
        requireIsolatedWriteTarget();
        return execute("ths-member-" + UUID.randomUUID(), null, request);
    }
    public Result finishInterrupted(String runId, boolean writerStopped) throws Exception {
        requireIsolatedWriteTarget();
        return ThsMemberRunRecovery.finish(targetAccess, ledgerPath, table, runId, writerStopped);
    }
    public Result executePrepared(String run, String parent, FrozenRequest request,
                                  List<ThsMember> rows, Path receipt) throws Exception {
        requireIsolatedWriteTarget();
        String board = validatePrepared(request);
        if (rows.isEmpty() || rows.size() >= ThsMemberState.MAX_BOARD_ROWS
                || rows.stream().anyMatch(row -> !board.equals(row.boardCode())))
            throw new IllegalArgumentException("One nonempty bounded THS board batch required");
        var mapper = new com.zoutrankil.data.index.mapper.ThsMemberMapper();
        var batch = DatasetWritePreparation.prepareWalReplace(ThsMemberDataset.DEFINITION, rows,
                mapper::values, new DatasetWritePreparation.Limits(10000, 16 * 1024 * 1024));
        String fingerprint = (String) request.parameters().get("payloadFingerprint");
        if (!batch.fingerprint().equals(fingerprint) || Files.size(receipt) > 16L * 1024 * 1024)
            throw new IllegalArgumentException("Prepared THS member batch differs from frozen fingerprint");
        var proof = JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(receipt,
                16 * 1024 * 1024, () -> new IllegalArgumentException("Prepared THS member batch differs from frozen fingerprint")));
        if (!proof.path("sourceKind").asText().equals("prepared-write-request")
                || !proof.path("targetId").asText().equals(targetId())
                || !proof.path("fingerprint").asText().equals(fingerprint)
                || !proof.path("board").asText().equals(board)
                || !JobDefinitionJson.mapper().valueToTree(batch.rows()).equals(proof.path("rows")))
            throw new IllegalArgumentException("Prepared THS member receipt differs from batch");
        return executeInternal(run, parent, request, new PreparedInput(rows, receipt, fingerprint));
    }
    public String revalidateGroupChild(String run, String expectedTarget, FrozenRequest request) throws Exception {
        String board = request.definition().jobId().equals("write.ths_member")
                ? validatePrepared(request) : validate(request);
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        var prior = ledger.getRun(run);
        if (ledger.get(run).state() != SyncRunState.VERIFIED
                || !prior.targetId().equals(expectedTarget)
                || !prior.frozenJson().equals(SyncRequestIdentity.snapshotJson(request)))
            throw new IllegalStateException("Prior THS member child differs from frozen write group");
        Path receipt = ledgerPath.getParent().resolve("sync-evidence").resolve(run).resolve("completion.json");
        var proof = JobDefinitionJson.mapper().readTree(receipt.toFile());
        if (!proof.path("runId").asText().equals(run) || !proof.path("board").asText().equals(board))
            throw new IllegalStateException("THS member completion differs from frozen child");
        var before = JobDefinitionJson.mapper().treeToValue(proof.path("before"), ThsMemberState.Snapshot.class);
        var saved = JobDefinitionJson.mapper().treeToValue(proof.path("actual"), ThsMemberState.Snapshot.class);
        var actual = targetAccess.open(table).snapshot(board);
        if (!expectedTarget.equals(targetAccess.identify( table,
                before.identity().id(), before.identity().directory())) || !saved.equals(actual))
            throw new IllegalStateException("Completed THS member target drifted");
        return receipt.toString();
    }
    public SyncJobRunner.Result runAsGroupChild(String child, String parent, String expectedTarget,
                                                FrozenRequest request) throws Exception {
        if (!targetId().equals(expectedTarget))
            throw new IllegalStateException("THS member group target changed before execution");
        var result = execute(child, parent, request);
        return new SyncJobRunner.Result(result.runId(), result.state(), result.sourceRows(),
                result.verifiedBoardRows(), result.errorCode());
    }
    public Result execute(String run, String parent, FrozenRequest request) throws Exception {
        return executeInternal(run, parent, request, null);
    }
    private Result executeInternal(String run, String parent, FrozenRequest request, PreparedInput input) throws Exception {
        requireIsolatedWriteTarget();
        String board = input == null ? validate(request) : validatePrepared(request);
        String target = targetId();
        var ledger = new SyncRunLedger(ledgerPath);
        var locks = new DatasetIntervalLock(ledgerPath);
        ledger.createRun(run, parent, target, request);
        Path folder = ledgerPath.getParent().resolve("sync-evidence").resolve(run);
        var lease = locks.acquire(run, DatasetIntervalLock.Scope.allDates(datasetId()));
        if (lease == null) {
            move(ledger, run, SyncRunState.FAILED, Map.of("errorCode", "DATASET_INTERVAL_BUSY"));
            return new Result(run, SyncRunState.FAILED, board, 0, 0, 0, 0, null,
                    folder.toString(), "DATASET_INTERVAL_BUSY");
        }
        String attempt = run + "-attempt", slice = run + "-board";
        var entries = new ArrayList<String>(); entries.add(run);
        long started = System.nanoTime();
        BooleanSupplier cancelled = () -> {
            if (Thread.currentThread().isInterrupted()
                    || System.nanoTime() - started > request.definition().timeout().toNanos()) return true;
            try { return ledger.cancellationRequested(run)
                    || parent != null && ledger.cancellationRequested(parent); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot read cancellation", failure); }
        };
        boolean submitted = false; int sourceCount = 0, beforeCount = 0; long copied = 0;
        String publication = null;
        try {
            move(ledger, run, SyncRunState.RUNNING, Map.of());
            ledger.createChild(attempt, SyncRunLedger.Kind.ATTEMPT, run, run); entries.addFirst(attempt);
            move(ledger, attempt, SyncRunState.RUNNING, Map.of());
            ledger.createChild(slice, SyncRunLedger.Kind.SLICE, run, attempt); entries.addFirst(slice);
            move(ledger, slice, SyncRunState.RUNNING, Map.of("board", board));
            check(cancelled); Files.createDirectories(folder);
            SyncJobRunner.Page<ThsMember> source;
            if (input == null) source = new ThsMemberSource(pages, folder).fetchBoard(board,
                    Instant.now().truncatedTo(ChronoUnit.MICROS), cancelled);
            else {
                Path receipt = input.receipt().toAbsolutePath().normalize();
                byte[] bytes = FileEvidenceStore.readBounded(receipt, 16 * 1024 * 1024, () -> new IllegalStateException("Prepared THS member receipt changed after admission"));
                var proof = JobDefinitionJson.mapper().readTree(bytes);
                var mapper = new com.zoutrankil.data.index.mapper.ThsMemberMapper();
                if (bytes.length > 16 * 1024 * 1024
                        || !proof.path("sourceKind").asText().equals("prepared-write-request")
                        || !proof.path("targetId").asText().equals(target)
                        || !proof.path("fingerprint").asText().equals(input.fingerprint())
                        || !proof.path("board").asText().equals(board)
                        || !JobDefinitionJson.mapper().valueToTree(input.rows().stream().map(mapper::values).toList())
                                .equals(proof.path("rows")))
                    throw new IllegalStateException("Prepared THS member receipt changed after admission");
                String hash = FileEvidenceStore.sha256(bytes);
                source = new SyncJobRunner.Page<>(input.rows(), hash, receipt.toString(), null);
            }
            sourceCount = source.rows().size();
            var staging = targetAccess.newStaging();
            var prepared = staging.prepare(table, board, source.rows());
            beforeCount = prepared.before().boardRows().size();
            copied = prepared.before().otherRows();
            if (sourceCount == 0 && beforeCount != 0)
                throw new IllegalStateException("Empty current board response cannot authorize deleting stored members");
            if (!target.equals(targetAccess.identify( table,
                    prepared.before().identity().id(), prepared.before().identity().directory())))
                throw new IllegalStateException("THS member target changed after run creation");
            FileEvidenceStore.writeNew(folder.resolve("prepared.json"),JobDefinitionJson.mapper().writeValueAsBytes(Map.of(
                    "runId", run, "targetId", target, "request", SyncRequestIdentity.snapshotJson(request),
                    "source", source, "prepared", prepared)));
            check(cancelled);
            if (staging.requiresWrite(prepared)) {
                submitted = true;
                var stage = staging.write(prepared, folder, cancelled);
                publication = new ThsMemberBoardPublication(targetAccess.publicationTables(), ledgerPath)
                        .publish(lease, prepared, stage, cancelled).publication().intent().id();
            }
            var actual = targetAccess.open(table).snapshot(board);
            if (actual.otherRows() != prepared.before().otherRows()
                    || !actual.otherFingerprint().equals(prepared.before().otherFingerprint()))
                throw new IllegalStateException("Unaffected THS boards changed");
            var expected = source.rows().stream().map(new com.zoutrankil.data.index.mapper.ThsMemberMapper()::toStorage)
                    .sorted(Comparator.comparing(com.zoutrankil.data.domain.table.ThsMemberRow::conCode)).toList();
            if (staging.requiresWrite(prepared) && !actual.boardRows().equals(expected))
                throw new IllegalStateException("Final THS member source-key full values differ");
            if (!staging.requiresWrite(prepared) && actual.boardRows().size() != sourceCount)
                throw new IllegalStateException("Unchanged THS board row count differs");
            var state = sourceCount == 0 ? SyncRunState.VERIFIED_EMPTY : SyncRunState.VERIFIED;
            Path receipt = folder.resolve("completion.json");
            var proof = new LinkedHashMap<String, Object>();
            proof.put("runId", run); proof.put("board", board); proof.put("source", source);
            proof.put("before", prepared.before()); proof.put("actual", actual);
            proof.put("publicationId", publication); proof.put("sourceRows", sourceCount);
            proof.put("verifiedRows", sourceCount); proof.put("copiedOtherRows", copied);
            FileEvidenceStore.writeNew(receipt,JobDefinitionJson.mapper().writeValueAsBytes(proof));
            var verification = Map.of("passed", true, "expectedRows", sourceCount, "actualRows", sourceCount,
                    "matchedRows", sourceCount, "mismatchedRows", 0, "duplicateKeys", 0, "missingKeys", 0,
                    "readbackEvidence", receipt.toString(), "sourceFingerprint", source.sourceFingerprint(),
                    "writerStopped", true);
            Map<String, Object> payload = Map.of("evidence", receipt.toString(), "checkpoint", actual.contentFingerprint(),
                    "verification", verification);
            if (sourceCount == 0)
                payload = Map.of("evidence", receipt.toString(), "checkpoint", actual.contentFingerprint(),
                        "verification", verification, "sourceComplete", true, "returnedRows", 0,
                        "submittedRows", 0, "responseEvidence", source.responseEvidence());
            for (String entry : entries) move(ledger, entry, state, payload);
            try { locks.releaseVerified(lease); } catch (RuntimeException ignored) { /* Retained for later reconciliation. */ }
            return new Result(run, state, board, sourceCount, beforeCount, actual.boardRows().size(),
                    copied, publication, receipt.toString(), null);
        } catch (Exception failure) {
            var state = submitted ? SyncRunState.IN_DOUBT
                    : (failure instanceof java.util.concurrent.CancellationException || cancelled.getAsBoolean())
                    ? SyncRunState.CANCELLED : SyncRunState.FAILED;
            var payload = Map.of("errorCode", failure.getClass().getSimpleName(),
                    "sourceRows", sourceCount, "evidence", folder.toString());
            for (String entry : entries) if (!ledger.get(entry).state().terminal()) move(ledger, entry, state, payload);
            var owned = locks.findOwned(run, lease.scope());
            if (submitted) { if (owned != null && !owned.inDoubt()) locks.retainInDoubt(owned); }
            else locks.releaseVerified(lease);
            return new Result(run, state, board, sourceCount, beforeCount, 0, copied, publication,
                    folder.toString(), failure.getClass().getSimpleName());
        }
    }

    private void requireIsolatedWriteTarget() {
        if (table.equals("ths_member")) throw new IllegalStateException(
                "Formal ths_member publication requires separate consumer cutover; configure an isolated table");
    }
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("THS member job cancelled or timed out");
    }
    private static void move(SyncRunLedger ledger, String id, SyncRunState state, Map<String, ?> payload) throws Exception {
        ledger.transition(id, ledger.get(id).revision(), state,
                JobDefinitionJson.mapper().writeValueAsString(payload));
    }
}
