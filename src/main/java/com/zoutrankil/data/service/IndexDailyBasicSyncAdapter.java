package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.policy.IndexDailyBasicUniverse;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.IndexDailyBasicWritePort;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** Executes one frozen D020 code/range slice and routes its one complete source page to the verified writer. */
public final class IndexDailyBasicSyncAdapter implements SyncJobRunner.Adapter<IndexDailyBasic,IndexDailyBasicKey> {
    private final IndexDailyBasicSource source;
    private final IndexDailyBasicWritePort port;
    private final Path evidenceRoot;
    public IndexDailyBasicSyncAdapter(IndexDailyBasicSource source, IndexDailyBasicWritePort port, Path evidenceRoot) {
        this.source = Objects.requireNonNull(source); this.port = Objects.requireNonNull(port);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }
    @Override public void preflight(SyncJobDefinition.FrozenRequest request) {
        if (request == null || !request.definition().equals(IndexDailyBasicSyncJobOwner.DEFINITION)
                || !request.definition().datasetId().equals(IndexDailyBasicDataset.DEFINITION.datasetId())
                || request.definition().datasetVersion() != IndexDailyBasicDataset.DEFINITION.schemaVersion()
                || !Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE).contains(request.mode())
                || request.from() == null || request.to() == null || request.to().isAfter(request.logicalDate())
                || ChronoUnit.DAYS.between(request.from(), request.to()) + 1 > IndexDailyBasicSource.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("Frozen bounded D020 request required");
        var params = request.parameters();
        Set<String> allowed = Set.of("targetId", "tsCode", "checkpointAnchor", "checkpointBefore", "targetMinBefore", "targetMaxBefore");
        if (!params.keySet().containsAll(Set.of("targetId", "tsCode")) || !allowed.containsAll(params.keySet()))
            throw new IllegalArgumentException("Unexpected or missing frozen D020 parameters");
        if (!(params.get("targetId") instanceof String target) || !target.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen isolated D020 target identity required");
        if (!(params.get("tsCode") instanceof String code) || !IndexDailyBasicUniverse.valid(code))
            throw new IllegalArgumentException("Frozen D020 code must belong to the five-code source universe");
        Object anchor = params.get("checkpointAnchor");
        if (request.mode() == Mode.INCREMENTAL) {
            if (!(anchor instanceof LocalDate date) || date.isAfter(request.to()) || request.from().isBefore(date))
                throw new IllegalArgumentException("Incremental D020 requires its frozen bootstrap anchor");
        } else if (anchor != null || params.get("checkpointBefore") != null)
            throw new IllegalArgumentException("Only incremental D020 requests may carry checkpoint metadata");
        for (String field : List.of("checkpointBefore", "targetMinBefore", "targetMaxBefore"))
            if (params.get(field) != null && !(params.get(field) instanceof LocalDate))
                throw new IllegalArgumentException("Invalid frozen D020 date: " + field);
        port.preflight();
    }
    @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<IndexDailyBasic> consumer, BooleanSupplier cancelled) throws Exception {
        String code = (String) request.parameters().get("tsCode");
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("D020 slice cancelled");
        var page = source.fetch(code, request.from(), request.to(), cancelled);
        var expectedKeys = new HashSet<IndexDailyBasicKey>();
        for (var row : page.rows()) if (!expectedKeys.add(row.key()))
            throw new IllegalStateException("D020 source response contains duplicate full keys");
        for (var existing : port.readRange(code, request.from(), request.to()))
            if (!expectedKeys.contains(existing.key()))
                throw new IllegalStateException("D020 source omitted an existing physical key; replacement requires explicit review");
        consumer.accept(page);
        Files.createDirectories(evidenceRoot); Path completion = evidenceRoot.resolve("complete-" + UUID.randomUUID() + ".json");
        var body = new LinkedHashMap<String,Object>(); body.put("endpoint", "index_dailybasic");
        body.put("tsCode", code); body.put("from", request.from()); body.put("to", request.to());
        body.put("slices", 1); body.put("sourceRows", page.rows().size());
        body.put("sourceEvidence", page.responseEvidence()); body.put("complete", true);
        JobDefinitionJson.canonicalMapper().writeValue(completion.toFile(), body);
        if (Files.size(completion) > IndexDailyBasicSource.MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("D020 completion evidence exceeds 16 MiB");
        return new SyncJobRunner.SourceCompletion(1, page.rows().size(), true, completion.toString());
    }
    @Override public VerifiedBatchExecutor.Codec<IndexDailyBasic,IndexDailyBasicKey> codec() { return IndexDailyBasicWritePort.CODEC; }
    @Override public VerifiedBatchExecutor.Port<IndexDailyBasic,IndexDailyBasicKey> port() { return port; }
}
