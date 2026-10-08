package com.zoutrankil.data.index.application;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.index.domain.IndexMonthlyState.*;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.IndexMonthlyDataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.IndexMonthly;
import com.zoutrankil.data.domain.IndexMonthlyKey;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.index.application.IndexMonthlySource;
import com.zoutrankil.data.index.application.IndexMonthlySyncJobOwner;
import com.zoutrankil.data.service.SyncJobRunner;


import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Creates a non-DEDUP complete target copy with one authoritative code/month window removed. */
public final class IndexMonthlyStaging {




    private static final int MAX_INTENT_BYTES = 1024 * 1024;
    private final IndexMonthlyStagingPort tables;

    public IndexMonthlyStaging(IndexMonthlyStagingPort tables){this.tables=Objects.requireNonNull(tables);}

    /**
     * The READY stage intent is durably bound to the frozen run and complete raw-source receipt before
     * generic writes can begin. A stage without READY and an exact physical identity is never publishable.
     */
    public Prepared prepare(String target, String logicalTargetId, String physicalTarget, String runId,
                            SyncJobDefinition.FrozenRequest request, String code, LocalDate from, LocalDate to,
                            String sourceReceipt, String sourceFingerprint, int sourceRows,
                            Path evidence, BooleanSupplier cancelled) throws Exception {
        IndexMonthlyDataset.requireIsolatedTableName(target);
        requireWindow(code, from, to);
        Objects.requireNonNull(request);
        check(cancelled);
        Instant observedAt = requireFrozenRequest(target, logicalTargetId, physicalTarget, request, code, from, to);
        String requestFingerprint = SyncRequestIdentity.fingerprint(request, logicalTargetId);
        Path stageRoot = evidence.toAbsolutePath().normalize();
        Files.createDirectories(stageRoot);
        Path runRoot = requireRunRoot(stageRoot, runId);
        Path rawReceipt = requireEvidenceFile(runRoot, sourceReceipt, IndexMonthlySource.MAX_EVIDENCE_BYTES);
        if (sourceFingerprint == null || !sourceFingerprint.matches("[0-9a-f]{64}") || sourceRows < 1
                || sourceRows > IndexMonthlySource.CLIENT_ROW_CAP || !sha256(FileEvidenceStore.readBounded(rawReceipt, IndexMonthlySource.MAX_EVIDENCE_BYTES,
                        () -> new IOException("D022 source receipt exceeds its evidence bound or escaped the run directory"))).equals(sourceFingerprint))
            throw new IllegalArgumentException("D022 complete bounded raw-source receipt required before stage creation");
        var sourcePage = IndexMonthlySource.reopen(rawReceipt, sourceFingerprint, code, from, to, observedAt);
        if (sourcePage.rows().size() != sourceRows)
            throw new IllegalArgumentException("D022 source receipt row count differs from the frozen stage intent");

        var storage = tables.open(target);
        var before = storage.snapshot();
        if (!tables.physicalTargetId( target, before.identity()).equals(physicalTarget))
            throw new IllegalStateException("D022 target physical generation changed before stage preparation");
        var outside = storage.outside(code, from, to);
        String stage = IndexMonthlyDataset.ISOLATED_PREFIX + "stage_" + UUID.randomUUID().toString().replace("-", "");
        String end = to.plusDays(1) + "T00:00:00.000000Z";
        String lower = from + "T00:00:00.000000Z";
        Path intent = stageRoot.resolve(stage + "-intent.json");
        Path normalizedIntent = intent.toAbsolutePath().normalize();
        if (!normalizedIntent.startsWith(runRoot) || Files.exists(intent, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("D022 stage intent path is not unique and run-owned");
        var intentBody = new LinkedHashMap<String, Object>();
        intentBody.put("dataset", "index_monthly");
        intentBody.put("phase", "PREPARING");
        intentBody.put("runId", runId);
        intentBody.put("target", target);
        intentBody.put("logicalTargetId", logicalTargetId);
        intentBody.put("physicalTargetBefore", physicalTarget);
        intentBody.put("stage", stage);
        intentBody.put("requestFingerprint", requestFingerprint);
        intentBody.put("mode", request.mode());
        intentBody.put("logicalDate", request.logicalDate());
        intentBody.put("code", code);
        intentBody.put("windowFrom", from);
        intentBody.put("windowTo", to);
        intentBody.put("observedAt", observedAt);
        intentBody.put("sourceReceipt", rawReceipt.toString());
        intentBody.put("sourceFingerprint", sourceFingerprint);
        intentBody.put("sourceRows", sourceRows);
        intentBody.put("sourceComplete", true);
        intentBody.put("before", proof(before));
        intentBody.put("preservedOutside", proof(outside));
        intentBody.put("dedup", false);
        writeNewDurable(intent, intentBody);

        if (tables.tableCount(stage) != 0)
            throw new IllegalStateException("D022 unique stage name already exists");
        tables.createOutsideStage(stage,target,code,lower,end);
        awaitWal(stage, cancelled);
        var stagedOutside = tables.open(stage).snapshot();
        if (!sameRows(outside.rows(), stagedOutside.rows()))
            throw new IllegalStateException("D022 non-DEDUP stage did not preserve the exact target rows outside the frozen window");
        String stageId = tables.physicalTargetId( stage, stagedOutside.identity());
        intentBody.put("stagePhysicalTarget", stageId);
        intentBody.put("phase", "READY");
        intentBody.put("stageCreated", stageCreatedProof(stagedOutside, stageId));
        replaceDurable(intent, intentBody);
        return new Prepared(target, stage, logicalTargetId, runId, requestFingerprint, physicalTarget, stageId,
                code, from, to, observedAt, rawReceipt.toString(), sourceFingerprint, sourceRows, before, outside);
    }

    /**
     * Reconstructs a stage-only operation only after proving frozen request, source event/receipt,
     * target generation, original full snapshot, preserved outside rows and complete staged contents.
     */
    public Recovered recover(String target, String logicalTargetId, String physicalTarget, String runId,
                             SyncJobDefinition.FrozenRequest request, SyncJobRunner.Page<IndexMonthly> fetched,
                             Path evidence) throws Exception {
        IndexMonthlyDataset.requireIsolatedTableName(target);
        Objects.requireNonNull(request);
        Objects.requireNonNull(fetched);
        Path stageRoot = evidence.toAbsolutePath().normalize();
        Path runRoot = requireRunRoot(stageRoot, runId);
        List<Path> intents = intentFiles(stageRoot);
        if (intents.size() != 1) throw new IllegalStateException("D022 stage-only recovery requires exactly one stage intent");
        Path intentPath = intents.getFirst().toRealPath();
        if (!intentPath.startsWith(runRoot) || Files.size(intentPath) > MAX_INTENT_BYTES)
            throw new IllegalStateException("D022 stage intent is outside the run evidence bound");
        JsonNode intent = JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(intentPath, MAX_INTENT_BYTES,
                () -> new IllegalStateException("D022 stage intent is outside the run evidence bound")));
        String code = required(intent, "code");
        LocalDate from = LocalDate.parse(required(intent, "windowFrom"));
        LocalDate to = LocalDate.parse(required(intent, "windowTo"));
        Instant observedAt = Instant.parse(required(intent, "observedAt"));
        String stage = required(intent, "stage");
        String sourcePath = required(intent, "sourceReceipt");
        String sourceFingerprint = required(intent, "sourceFingerprint");
        int sourceRows = intent.path("sourceRows").asInt(-1);
        String requestFingerprint = SyncRequestIdentity.fingerprint(request, logicalTargetId);
        String expectedTargetId = (String) request.parameters().get("targetId");
        String expectedPhysicalId = (String) request.parameters().get("physicalTargetId");
        if (!"index_monthly".equals(intent.path("dataset").asText())
                || !"READY".equals(intent.path("phase").asText())
                || !intent.path("sourceComplete").asBoolean(false) || intent.path("dedup").asBoolean(true)
                || !runId.equals(intent.path("runId").asText())
                || !target.equals(intent.path("target").asText())
                || !logicalTargetId.equals(intent.path("logicalTargetId").asText())
                || !physicalTarget.equals(intent.path("physicalTargetBefore").asText())
                || !physicalTarget.equals(expectedPhysicalId) || !logicalTargetId.equals(expectedTargetId)
                || !requestFingerprint.equals(intent.path("requestFingerprint").asText())
                || !request.mode().name().equals(intent.path("mode").asText())
                || !request.logicalDate().toString().equals(intent.path("logicalDate").asText())
                || !code.equals(request.parameters().get("tsCode")) || !from.equals(request.from()) || !to.equals(request.to())
                || !stage.matches("java_d022_index_monthly_stage_[0-9a-f]{32}")
                || !sourceFingerprint.matches("[0-9a-f]{64}") || sourceRows < 1
                || sourceRows > IndexMonthlySource.CLIENT_ROW_CAP)
            throw new IllegalStateException("D022 stage-only intent differs from the exact frozen isolated request");
        if (!observedAt.equals(requireFrozenRequest(target, logicalTargetId, physicalTarget, request, code, from, to)))
            throw new IllegalStateException("D022 stage observation timestamp differs from the frozen request");

        Path source = requireEvidenceFile(runRoot, sourcePath, IndexMonthlySource.MAX_EVIDENCE_BYTES);
        String fetchedReceipt = Path.of(fetched.responseEvidence()).toAbsolutePath().normalize().toString();
        if (!source.toString().equals(fetchedReceipt) || !fetched.cursor().equals(code)
                || !fetched.sourceFingerprint().equals(sourceFingerprint) || fetched.rows().size() != sourceRows
                || !sha256(FileEvidenceStore.readBounded(source, IndexMonthlySource.MAX_EVIDENCE_BYTES,
                        () -> new IOException("D022 source receipt exceeds its evidence bound or escaped the run directory"))).equals(sourceFingerprint))
            throw new IllegalStateException("D022 fetched ledger event and stage source evidence differ");
        var reopened = IndexMonthlySource.reopen(source, sourceFingerprint, code, from, to, observedAt);
        if (!sameRows(reopened.rows(), fetched.rows()))
            throw new IllegalStateException("D022 recovered source page differs from the immutable raw receipt");

        var targetStorage = tables.open(target);
        var before = targetStorage.snapshot();
        if (!tables.physicalTargetId( target, before.identity()).equals(physicalTarget)
                || !sameIdentityAndContent(before, intent.path("before")))
            throw new IllegalStateException("D022 physical target/full snapshot changed since the stage-only intent");
        var outside = targetStorage.outside(code, from, to);
        if (!sameIdentityAndContent(outside, intent.path("preservedOutside")))
            throw new IllegalStateException("D022 preserved outside-window proof differs from the current target");
        int named = tables.tableCount(stage);
        if (named != 1) throw new IllegalStateException("D022 exact stage table is absent or ambiguous");
        var stageStorage = tables.open(stage);
        var staged = stageStorage.snapshot();
        String stagePhysical = required(intent, "stagePhysicalTarget");
        JsonNode stageCreated = intent.path("stageCreated");
        JsonNode stageCreatedIdentity = stageCreated.path("identity");
        var stageOutsideNow = stageStorage.outside(code, from, to);
        if (!stagePhysical.matches("static-v2-[0-9a-f]{64}")
                || stagePhysical.equals(physicalTarget)
                || !tables.physicalTargetId( stage, staged.identity()).equals(stagePhysical)
                || !stageCreatedIdentity.path("id").isIntegralNumber()
                || stageCreatedIdentity.path("id").longValue() != staged.identity().id()
                || !stageCreatedIdentity.path("directory").asText().equals(staged.identity().directory())
                || !stagePhysical.equals(stageCreated.path("physicalTargetId").asText())
                || !stageCreated.path("initialOutside").path("fingerprint").asText().equals(outside.fingerprint())
                || stageCreated.path("initialOutside").path("rows").asInt(-1) != outside.rows().size()
                || !sameRows(outside.rows(), stageOutsideNow.rows()))
            throw new IllegalStateException("D022 physical stage generation differs from its durable READY intent");
        var expected = new ArrayList<>(outside.rows());
        expected.addAll(reopened.rows());
        if (!sameRows(ordered(expected), staged.rows()))
            throw new IllegalStateException("D022 stage-only recovery refuses an incomplete or extra-row stage");

        var prepared = new Prepared(target, stage, logicalTargetId, runId, requestFingerprint, physicalTarget,
                stagePhysical, code, from, to, observedAt, source.toString(), sourceFingerprint, sourceRows,
                before, outside);
        Path recoveryReceipts = stageRoot.resolve("recovery-" + UUID.randomUUID());
        var verified = verify(prepared, reopened.rows(), sourceFingerprint, source.toString(), recoveryReceipts, () -> false);
        return new Recovered(prepared, verified);
    }

    /** True when a run-owned stage intent exists, including incomplete PREPARING artifacts. */
    public static boolean hasStageIntent(Path runEvidence) throws IOException {
        Path stageRoot = runEvidence.toAbsolutePath().normalize().resolve("stage");
        if (!Files.exists(stageRoot, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(stageRoot) || !Files.isDirectory(stageRoot, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("D022 stage evidence directory is not a regular directory");
        return !intentFiles(stageRoot).isEmpty();
    }

    /** Called only after generic bounded batches have written and read back from the stage. */
    public Verified verify(Prepared prepared, List<IndexMonthly> authoritativeRows, String sourceFingerprint,
                           String sourceReceipt, Path evidence, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(prepared);
        Objects.requireNonNull(authoritativeRows);
        check(cancelled);
        if (!prepared.requestFingerprint().matches("[0-9a-f]{64}")
                || sourceFingerprint == null || !sourceFingerprint.matches("[0-9a-f]{64}")
                || !prepared.sourceFingerprint().equals(sourceFingerprint)
                || !prepared.sourceReceipt().equals(Path.of(sourceReceipt).toAbsolutePath().normalize().toString())
                || authoritativeRows.size() != prepared.sourceRows())
            throw new IllegalArgumentException("D022 source evidence differs from the stage's frozen request/receipt");
        var source = ordered(authoritativeRows);
        if (source.size() != authoritativeRows.size() || source.stream().anyMatch(row ->
                !row.tsCode().equals(prepared.code()) || row.tradeDate().isBefore(prepared.from()) || row.tradeDate().isAfter(prepared.to())))
            throw new IllegalArgumentException("D022 authoritative rows do not match the frozen code/month window");
        var keys = new HashSet<IndexMonthlyKey>();
        for (var row : source) if (!keys.add(row.key())) throw new IllegalArgumentException("Duplicate D022 source natural key");
        var expected = new ArrayList<>(prepared.outside().rows());
        expected.addAll(source);
        expected = new ArrayList<>(ordered(expected));
        var storage = tables.open(prepared.stage());
        var actual = storage.snapshot();
        if (!tables.physicalTargetId( prepared.stage(), actual.identity()).equals(prepared.stagePhysicalTarget())
                || !sameRows(expected, actual.rows()))
            throw new IllegalStateException("D022 full non-DEDUP staged snapshot differs from preserved outside rows plus source window");
        var outsideNow = storage.outside(prepared.code(), prepared.from(), prepared.to());
        var windowNow = storage.window(prepared.code(), prepared.from(), prepared.to());
        if (!sameRows(prepared.outside().rows(), outsideNow.rows()) || !sameRows(source, windowNow.rows()))
            throw new IllegalStateException("D022 stage outside/window readback mismatch");
        Files.createDirectories(evidence);
        Path receipt = evidence.resolve(prepared.stage() + "-verified.json");
        var body = new LinkedHashMap<String, Object>();
        body.put("dataset", "index_monthly");
        body.put("runId", prepared.runId());
        body.put("target", prepared.target());
        body.put("logicalTargetId", prepared.logicalTargetId());
        body.put("requestFingerprint", prepared.requestFingerprint());
        body.put("stage", prepared.stage());
        body.put("physicalTargetBefore", prepared.physicalTargetBefore());
        body.put("stagePhysicalTarget", prepared.stagePhysicalTarget());
        body.put("before", proof(prepared.before()));
        body.put("after", proof(actual));
        body.put("code", prepared.code());
        body.put("windowFrom", prepared.from());
        body.put("windowTo", prepared.to());
        body.put("observedAt", prepared.observedAt());
        body.put("preservedOutside", proof(outsideNow));
        body.put("authoritativeWindow", proof(windowNow));
        body.put("sourceRows", source.size());
        body.put("sourceFingerprint", sourceFingerprint);
        body.put("sourceReceipt", Path.of(sourceReceipt).toAbsolutePath().normalize().toString());
        body.put("sourceComplete", true);
        body.put("dedup", false);
        writeNewDurable(receipt, body);
        awaitWal(prepared.stage(), cancelled);
        return new Verified(prepared.stage(), prepared.stagePhysicalTarget(), actual, outsideNow, windowNow,
                source.size(), sourceFingerprint, Path.of(sourceReceipt).toAbsolutePath().normalize().toString(), receipt.toString());
    }

    public static String fingerprint(List<IndexMonthly> rows) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (var row : ordered(rows)) {
            digest.update(IndexMonthlyRows.canonicalBytes(row));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static Map<String, Object> proof(IndexMonthlyState.Snapshot snapshot) {
        return Map.of("identity", snapshot.identity(), "rows", snapshot.rows().size(),
                "fingerprint", snapshot.fingerprint(), "bytes", snapshot.bytes());
    }

    public static List<IndexMonthly> ordered(List<IndexMonthly> rows) {
        return rows.stream().sorted(Comparator.comparing(IndexMonthly::tradeDate).thenComparing(IndexMonthly::tsCode)).toList();
    }

    private void awaitWal(String table,BooleanSupplier cancelled)throws Exception {tables.awaitWal(table,cancelled);}

    private static boolean sameIdentityAndContent(IndexMonthlyState.Snapshot snapshot, JsonNode proof) {
        if (snapshot == null || proof == null || !proof.isObject()
                || !fieldNames(proof).equals(Set.of("identity", "rows", "fingerprint", "bytes"))) return false;
        JsonNode identity = proof.path("identity");
        if (!identity.isObject() || !fieldNames(identity).equals(Set.of("id", "directory", "writerTxn"))) return false;
        return exactLong(identity.path("id"), snapshot.identity().id())
                && identity.path("directory").isTextual()
                && identity.path("directory").asText().equals(snapshot.identity().directory())
                && exactLong(identity.path("writerTxn"), snapshot.identity().writerTxn())
                && exactLong(proof.path("rows"), snapshot.rows().size())
                && proof.path("fingerprint").isTextual()
                && proof.path("fingerprint").asText().equals(snapshot.fingerprint())
                && exactLong(proof.path("bytes"), snapshot.bytes());
    }

    private static boolean exactLong(JsonNode node, long expected) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() == expected;
    }

    private static Set<String> fieldNames(JsonNode node) {
        var names = new HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Map<String, Object> stageCreatedProof(IndexMonthlyState.Snapshot snapshot, String physicalTargetId) {
        return Map.of("identity", snapshot.identity(), "physicalTargetId", physicalTargetId,
                "initialOutside", Map.of("rows", snapshot.rows().size(), "fingerprint", snapshot.fingerprint(),
                        "bytes", snapshot.bytes()));
    }

    private static boolean sameRows(List<IndexMonthly> expected, List<IndexMonthly> actual) throws Exception {
        if (expected.size() != actual.size() || !fingerprint(expected).equals(fingerprint(actual))) return false;
        var left = ordered(expected);
        var right = ordered(actual);
        for (int i = 0; i < left.size(); i++) if (!Arrays.equals(IndexMonthlyRows.canonicalBytes(left.get(i)),
                IndexMonthlyRows.canonicalBytes(right.get(i)))) return false;
        return true;
    }

    private static Instant requireFrozenRequest(String target, String logicalTargetId, String physicalTarget,
                                                SyncJobDefinition.FrozenRequest request, String code,
                                                LocalDate from, LocalDate to) {
        var parameters = request.parameters();
        if (!request.definition().equals(IndexMonthlySyncJobOwner.DEFINITION)
                || !"index_monthly".equals(request.definition().datasetId())
                || !logicalTargetId.equals(parameters.get("targetId"))
                || !physicalTarget.equals(parameters.get("physicalTargetId"))
                || !code.equals(parameters.get("tsCode")) || !from.equals(request.from()) || !to.equals(request.to())
                || !Set.of(SyncJobDefinition.Mode.INCREMENTAL, SyncJobDefinition.Mode.BACKFILL,
                SyncJobDefinition.Mode.RECONCILE).contains(request.mode()))
            throw new IllegalArgumentException("D022 stage context must equal the complete frozen isolated request");
        Object observed = parameters.get("observedAt");
        if (!(observed instanceof String value)) throw new IllegalArgumentException("D022 frozen observation timestamp required");
        Instant parsed;
        try { parsed = Instant.parse(value); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid D022 frozen observation timestamp", invalid); }
        TemporalValues.requirePrecision(parsed, TemporalValues.Precision.MICROS);
        return parsed;
    }

    private static Path requireRunRoot(Path stageRoot, String runId) throws IOException {
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))
            throw new IllegalArgumentException("Valid D022 run ID required");
        if (!"stage".equals(stageRoot.getFileName().toString()) || Files.isSymbolicLink(stageRoot)
                || !Files.isDirectory(stageRoot, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("D022 stage evidence must be a regular run-owned directory");
        Path runRoot = stageRoot.getParent().toRealPath();
        if (!runRoot.getFileName().toString().equals(runId) || !stageRoot.toRealPath().startsWith(runRoot))
            throw new IOException("D022 stage evidence is outside its frozen run directory");
        return runRoot;
    }

    private static Path requireEvidenceFile(Path runRoot, String raw, long maxBytes) throws IOException {
        Path candidate = Path.of(raw).toAbsolutePath().normalize();
        if (!candidate.startsWith(runRoot) || Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("D022 source receipt is absent, symlinked or outside the frozen run directory");
        Path resolved = candidate.toRealPath();
        if (!resolved.startsWith(runRoot) || Files.size(resolved) < 1 || Files.size(resolved) > maxBytes)
            throw new IOException("D022 source receipt exceeds its evidence bound or escaped the run directory");
        return resolved;
    }

    private static List<Path> intentFiles(Path stageRoot) throws IOException {
        if (Files.isSymbolicLink(stageRoot) || !Files.isDirectory(stageRoot, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("D022 stage intent directory is not a regular directory");
        var found = new ArrayList<Path>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(stageRoot, "*-intent.json")) {
            for (Path path : paths) {
                if (found.size() == 1) throw new IOException("Multiple D022 stage intents make recovery ambiguous");
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(path) < 1 || Files.size(path) > MAX_INTENT_BYTES)
                    throw new IOException("D022 stage intent is symlinked or outside its bounded file size");
                found.add(path);
            }
        }
        return List.copyOf(found);
    }

    private static void writeNewDurable(Path path, Object body) throws Exception {
        byte[] bytes = JobDefinitionJson.mapper().writeValueAsBytes(body);
        if (bytes.length < 1 || bytes.length > MAX_INTENT_BYTES) throw new IllegalArgumentException("D022 stage evidence size bound exceeded");
        FileEvidenceStore.writeNewDurable(path, bytes);
    }

    private static void replaceDurable(Path path, Object body) throws Exception {
        byte[] bytes = JobDefinitionJson.mapper().writeValueAsBytes(body);
        if (bytes.length < 1 || bytes.length > MAX_INTENT_BYTES) throw new IllegalArgumentException("D022 stage evidence size bound exceeded");
        try {
            FileEvidenceStore.replaceDurable(path, bytes);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("D022 stage READY intent requires an atomic durable replace", unsupported);
        }
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException("D022 stage intent lacks " + field);
        return value.asText();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return FileEvidenceStore.sha256(bytes);
    }



    private static void requireWindow(String code, LocalDate from, LocalDate to) {
        if (!com.zoutrankil.data.domain.policy.IndexMonthlyUniverse.validProviderCode(code)
                || from == null || to == null || from.isAfter(to)) throw new IllegalArgumentException("Bounded D022 window required");
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("D022 stage cancelled; retain stage");
    }
}
