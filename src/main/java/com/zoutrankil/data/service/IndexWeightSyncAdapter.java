package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.IndexWeight;
import com.zoutrankil.data.domain.IndexWeightDataset;
import com.zoutrankil.data.domain.IndexWeightKey;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.mapper.IndexWeightMapper;
import com.zoutrankil.data.repository.IndexWeightWritePort;
import com.zoutrankil.data.service.VerifiedBatchExecutor.Codec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** D021 source-to-runner bridge with full-scope stale-key admission before the first write. */
public final class IndexWeightSyncAdapter implements SyncJobRunner.Adapter<IndexWeight, IndexWeightKey> {
    private static final int MAX_COMPLETION_BYTES = 32 * 1024 * 1024;
    private record Group(String indexCode, LocalDate date) {}

    private final IndexWeightSource source;
    private final IndexWeightWritePort port;
    private final Path evidenceRoot;

    public IndexWeightSyncAdapter(IndexWeightSource source, IndexWeightWritePort port, Path evidenceRoot) {
        this.source = Objects.requireNonNull(source);
        this.port = Objects.requireNonNull(port);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    @Override public void preflight(FrozenRequest request) {
        validateShape(request);
        port.preflight();
    }

    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,
            SyncJobRunner.PageConsumer<IndexWeight> consumer, BooleanSupplier cancelled) throws Exception {
        validateShape(request);
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new CancellationException("D021 cancelled before source fetch");

        // IndexWeightSource completes and validates every provider request before returning any page.
        IndexWeightSource.Result result = source.fetch(request, cancelled);
        List<IndexWeight> allRows = result.pages().stream().flatMap(page -> page.rows().stream())
                .sorted(Comparator.comparing(IndexWeight::indexCode).thenComparing(IndexWeight::tradeDate)
                        .thenComparing(IndexWeight::conCode)).toList();
        var seen = new HashSet<IndexWeightKey>();
        for (var row : allRows) if (!seen.add(row.key()))
            throw new IllegalArgumentException("D021 source contains duplicate keys across provider calls");

        Map<Group, List<IndexWeight>> expected = grouped(allRows);
        validateExistingBeforeWrite(request, expected);

        int pages = 0, rows = 0;
        var pageEvidence = new ArrayList<Map<String, Object>>();
        for (var page : result.pages()) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new CancellationException("D021 cancelled between source pages");
            consumer.accept(page);
            pages++;
            rows = Math.addExact(rows, page.rows().size());
            pageEvidence.add(Map.of("cursor", page.cursor(), "rows", page.rows().size(),
                    "sourceFingerprint", page.sourceFingerprint(), "responseEvidence", page.responseEvidence()));
        }

        validatePhysicalAfterWrite(request, expected);
        Files.createDirectories(evidenceRoot);
        Path complete = evidenceRoot.resolve("complete-" + UUID.randomUUID() + ".json");
        var body = new LinkedHashMap<String, Object>();
        body.put("datasetId", IndexWeightDataset.DEFINITION.datasetId());
        body.put("sourceContractVersion", 1); body.put("mode", request.mode());
        body.put("from", request.from()); body.put("to", request.to());
        body.put("logicalDate", request.logicalDate()); body.put("observedAt", request.parameters().get("observedAt"));
        body.put("targetId", request.parameters().get("targetId"));
        body.put("sourceRows", result.sourceRows()); body.put("emittedRows", rows);
        body.put("pages", pages); body.put("calls", result.calls()); body.put("pageEvidence", pageEvidence);
        body.put("targetComparison", "all source keys and physical rows matched after runner readback");
        body.put("complete", result.complete());
        byte[] bytes = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsBytes(body);
        if (bytes.length > MAX_COMPLETION_BYTES)
            throw new IllegalArgumentException("D021 completion receipt exceeds 32 MiB");
        writeImmutable(complete, bytes);
        return new SyncJobRunner.SourceCompletion(pages, rows, result.complete(), complete.toString());
    }

    @Override public Codec<IndexWeight, IndexWeightKey> codec() { return IndexWeightWritePort.CODEC; }
    @Override public VerifiedBatchExecutor.Port<IndexWeight, IndexWeightKey> port() { return port; }

    private void validateExistingBeforeWrite(FrozenRequest request, Map<Group, List<IndexWeight>> expected) {
        if (request.mode() == Mode.SNAPSHOT) {
            for (var entry : expected.entrySet()) {
                List<IndexWeight> actual = port.readSnapshot(entry.getKey().indexCode(), entry.getKey().date());
                if (!isKeySubset(actual, entry.getValue()))
                    throw new IllegalStateException("Existing D021 snapshot contains keys outside its complete source: "
                            + entry.getKey().indexCode() + "@" + entry.getKey().date());
            }
            return;
        }
        for (var index : IndexWeightUniverse.INDEXES) {
            List<IndexWeight> actual = port.readRange(index.code(), request.from(), request.to());
            var actualGroups = grouped(actual);
            var expectedGroups = new HashMap<Group, List<IndexWeight>>();
            expected.forEach((key, value) -> { if (key.indexCode().equals(index.code())) expectedGroups.put(key, value); });
            if (!isKeyInventorySubset(actualGroups, expectedGroups))
                throw new IllegalStateException("Existing D021 backfill range is not exactly explained by the complete source: " + index.code());
        }
    }

    private void validatePhysicalAfterWrite(FrozenRequest request, Map<Group, List<IndexWeight>> expected) {
        if (request.mode() == Mode.SNAPSHOT) {
            for (var entry : expected.entrySet()) {
                List<IndexWeight> actual = port.readSnapshot(entry.getKey().indexCode(), entry.getKey().date());
                if (!sameRows(entry.getValue(), actual))
                    throw new IllegalStateException("D021 physical snapshot differs from complete source after write: "
                            + entry.getKey().indexCode() + "@" + entry.getKey().date());
            }
            return;
        }
        for (var index : IndexWeightUniverse.INDEXES) {
            List<IndexWeight> actual = port.readRange(index.code(), request.from(), request.to());
            var expectedRows = expected.values().stream().flatMap(List::stream)
                    .filter(row -> row.indexCode().equals(index.code())).toList();
            if (!sameRows(expectedRows, actual))
                throw new IllegalStateException("D021 physical backfill range differs from complete source after write: " + index.code());
        }
    }

    private static Map<Group, List<IndexWeight>> grouped(List<IndexWeight> rows) {
        var grouped = new HashMap<Group, List<IndexWeight>>();
        for (var row : rows) grouped.computeIfAbsent(new Group(row.indexCode(), row.tradeDate()), ignored -> new ArrayList<>()).add(row);
        grouped.replaceAll((key, value) -> value.stream().sorted(Comparator.comparing(IndexWeight::conCode)).toList());
        return grouped;
    }

    /** Existing physical keys may be a subset on first load, but may never include a key absent from source. */
    private static boolean isKeyInventorySubset(Map<Group, List<IndexWeight>> actual, Map<Group, List<IndexWeight>> expected) {
        for (var entry : actual.entrySet()) {
            List<IndexWeight> sourceRows = expected.get(entry.getKey());
            if (sourceRows == null || !isKeySubset(entry.getValue(), sourceRows)) return false;
        }
        return true;
    }

    private static boolean isKeySubset(List<IndexWeight> subset, List<IndexWeight> superset) {
        if (subset.size() > superset.size()) return false;
        var allowed = superset.stream().map(IndexWeight::key).collect(java.util.stream.Collectors.toSet());
        var found = new HashSet<IndexWeightKey>();
        for (var row : subset) if (!found.add(row.key()) || !allowed.contains(row.key())) return false;
        return true;
    }

    private static boolean sameKeys(List<IndexWeight> expected, List<IndexWeight> actual) {
        if (expected.size() != actual.size()) return false;
        var left = expected.stream().map(IndexWeight::key).collect(java.util.stream.Collectors.toSet());
        var right = actual.stream().map(IndexWeight::key).collect(java.util.stream.Collectors.toSet());
        return left.size() == expected.size() && right.size() == actual.size() && left.equals(right);
    }

    private static boolean sameRows(List<IndexWeight> expected, List<IndexWeight> actual) {
        if (expected.size() != actual.size()) return false;
        var left = new HashMap<IndexWeightKey, byte[]>(); var right = new HashMap<IndexWeightKey, byte[]>();
        for (var row : expected) if (left.putIfAbsent(row.key(), IndexWeightWritePort.CODEC.canonicalBytes(row)) != null) return false;
        for (var row : actual) if (right.putIfAbsent(row.key(), IndexWeightWritePort.CODEC.canonicalBytes(row)) != null) return false;
        return left.keySet().equals(right.keySet())
                && left.keySet().stream().allMatch(key -> Arrays.equals(left.get(key), right.get(key)));
    }

    private static void validateShape(FrozenRequest request) {
        if (request == null || !request.definition().equals(IndexWeightSyncJobOwner.DEFINITION)
                || request.definition().datasetVersion() != IndexWeightDataset.DEFINITION.schemaVersion()
                || !Set.of(Mode.SNAPSHOT, Mode.BACKFILL).contains(request.mode()))
            throw new IllegalArgumentException("Frozen D021 snapshot or bounded backfill request required");
        Set<String> allowed = Set.of("targetId", "stockDetailTargetId", "observedAt", "force", "targetMinBefore", "targetMaxBefore");
        var params = request.parameters();
        if (!params.keySet().containsAll(Set.of("targetId", "stockDetailTargetId", "observedAt")) || !allowed.containsAll(params.keySet()))
            throw new IllegalArgumentException("Unexpected or missing frozen D021 parameters");
        if (!(params.get("targetId") instanceof String id) || !id.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen physical D021 target identity required");
        if (!(params.get("stockDetailTargetId") instanceof String referenceId) || !referenceId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D021 name-reference target identity required");
        if (params.get("force") != null && !(params.get("force") instanceof Boolean))
            throw new IllegalArgumentException("Invalid frozen D021 force flag");
        for (String name : List.of("targetMinBefore", "targetMaxBefore"))
            if (params.get(name) != null && !(params.get(name) instanceof LocalDate))
                throw new IllegalArgumentException("Invalid frozen D021 target range: " + name);
        if ((params.get("targetMinBefore") == null) != (params.get("targetMaxBefore") == null))
            throw new IllegalArgumentException("Both frozen D021 target range endpoints are required");
        String observedAtText = params.get("observedAt") instanceof String text ? text : "";
        java.time.Instant observed;
        try { observed = java.time.Instant.parse(observedAtText); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Canonical frozen D021 observedAt required", invalid); }
        com.zoutrankil.data.domain.temporal.TemporalValues.requirePrecision(observed,
                com.zoutrankil.data.domain.temporal.TemporalValues.Precision.MICROS);
        if (!observed.toString().equals(observedAtText)) throw new IllegalArgumentException("Canonical frozen D021 observedAt required");
        if (request.mode() == Mode.SNAPSHOT) {
            if (request.from() != null || request.to() != null)
                throw new IllegalArgumentException("D021 snapshot must not claim a synthetic trade_date interval");
        } else {
            long days = ChronoUnit.DAYS.between(request.from(), request.to()) + 1;
            if (days < 1 || days > IndexWeightSyncJobOwner.MAX_BACKFILL_DAYS
                    || request.to().isAfter(request.logicalDate()))
                throw new IllegalArgumentException("D021 backfill must be bounded to 1..92 days through logicalDate");
        }
        IndexWeightJobService.requireIsolatedTargetParameter(params);
    }

    private static void writeImmutable(Path path, byte[] bytes) throws Exception {
        Files.createDirectories(path.toAbsolutePath().normalize().getParent());
        try { Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
        catch (java.nio.file.FileAlreadyExistsException exists) {
            if (!java.security.MessageDigest.isEqual(Files.readAllBytes(path), bytes))
                throw new IllegalStateException("D021 immutable completion evidence path conflict", exists);
        }
    }
}
