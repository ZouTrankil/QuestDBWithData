package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.IndexDailyMarketWritePort;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode;

/** One frozen ts_code × date-range source slice. The caller schedules CORE57/SW2021_L1_31 members explicitly. */
public final class IndexDailyMarketSyncAdapter implements SyncJobRunner.Adapter<IndexDailyMarket,IndexDailyMarketKey> {
    private final IndexDailyMarketSource source;
    private final IndexDailyMarketWritePort port;
    private final Path evidenceRoot;
    public IndexDailyMarketSyncAdapter(IndexDailyMarketSource source, IndexDailyMarketWritePort port, Path evidenceRoot) {
        this.source = Objects.requireNonNull(source); this.port = Objects.requireNonNull(port);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }
    @Override public void preflight(SyncJobDefinition.FrozenRequest request) {
        if (request == null || !request.definition().equals(IndexDailyMarketSyncJobOwner.DEFINITION)
                || !request.definition().datasetId().equals(IndexDailyMarketDataset.DEFINITION.datasetId())
                || request.definition().datasetVersion() != IndexDailyMarketDataset.DEFINITION.schemaVersion()
                || !Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE).contains(request.mode())
                || request.from() == null || request.to() == null || request.to().isAfter(request.logicalDate())
                || ChronoUnit.DAYS.between(request.from(), request.to()) + 1 > IndexDailyMarketSource.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("Frozen bounded D019 request required");
        var parameters = request.parameters();
        Set<String> allowed = Set.of("targetId", "tsCode", "route", "observedAt", "checkpointAnchor",
                "checkpointBefore", "targetMinBefore", "targetMaxBefore");
        if (!parameters.keySet().containsAll(Set.of("targetId", "tsCode", "route", "observedAt"))
                || !allowed.containsAll(parameters.keySet())) throw new IllegalArgumentException("Unexpected/missing frozen D019 parameters");
        if (!(parameters.get("targetId") instanceof String target) || !target.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen isolated D019 target identity required");
        if (!(parameters.get("tsCode") instanceof String code)) throw new IllegalArgumentException("Frozen D019 tsCode required");
        var index = IndexDailyMarketUniverse.resolve(code);
        if (index == null || !index.route().name().equals(parameters.get("route")))
            throw new IllegalArgumentException("D019 endpoint route must match the frozen source universe");
        if (!(parameters.get("observedAt") instanceof String observation)) throw new IllegalArgumentException("Frozen D019 observation timestamp required");
        Instant observedAt;
        try { observedAt = Instant.parse(observation); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid frozen D019 observation timestamp", invalid); }
        if (!observedAt.equals(observedAt.truncatedTo(ChronoUnit.MICROS))) throw new IllegalArgumentException("D019 observation time must have microsecond precision");
        Object anchor = parameters.get("checkpointAnchor");
        if (request.mode() == Mode.INCREMENTAL) {
            if (!(anchor instanceof LocalDate date) || date.isAfter(request.to()) || request.from().isBefore(date))
                throw new IllegalArgumentException("INCREMENTAL D019 requires its frozen bootstrap anchor");
        } else if (anchor != null || parameters.get("checkpointBefore") != null)
            throw new IllegalArgumentException("Only incremental D019 plans may carry checkpoint metadata");
        for (String field : List.of("checkpointBefore", "targetMinBefore", "targetMaxBefore")) {
            Object value = parameters.get(field);
            if (value != null && !(value instanceof LocalDate)) throw new IllegalArgumentException("Invalid frozen D019 date: " + field);
        }
        port.preflight();
    }
    @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<IndexDailyMarket> consumer, BooleanSupplier cancelled) throws Exception {
        String code = (String) request.parameters().get("tsCode");
        Instant observedAt = Instant.parse((String) request.parameters().get("observedAt"));
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("D019 source slice cancelled");
        var page = source.fetch(code, request.from(), request.to(), observedAt, cancelled);
        var expectedKeys = new HashSet<IndexDailyMarketKey>();
        for (var row : page.rows()) if (!expectedKeys.add(row.key()))
            throw new IllegalStateException("D019 source response contains duplicate full keys");
        for (var existing : port.readRange(code, request.from(), request.to()))
            if (!expectedKeys.contains(existing.key()))
                throw new IllegalStateException("D019 source omitted an existing physical key; fail closed for manual correction/deletion review");
        consumer.accept(page);
        Files.createDirectories(evidenceRoot); Path completion = evidenceRoot.resolve("complete-" + UUID.randomUUID() + ".json");
        var body = new LinkedHashMap<String,Object>(); body.put("endpoint", IndexDailyMarketUniverse.endpoint(IndexDailyMarketUniverse.resolve(code).route()));
        body.put("tsCode", code); body.put("from", request.from()); body.put("to", request.to());
        body.put("observedAt", observedAt); body.put("slices", 1); body.put("sourceRows", page.rows().size());
        body.put("sourceEvidence", page.responseEvidence()); body.put("complete", true);
        JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).writeValue(completion.toFile(), body);
        if (Files.size(completion) > IndexDailyMarketSource.MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("D019 completion evidence exceeds 32 MiB");
        return new SyncJobRunner.SourceCompletion(1, page.rows().size(), true, completion.toString());
    }
    @Override public VerifiedBatchExecutor.Codec<IndexDailyMarket,IndexDailyMarketKey> codec() { return IndexDailyMarketWritePort.CODEC; }
    @Override public VerifiedBatchExecutor.Port<IndexDailyMarket,IndexDailyMarketKey> port() { return port; }
}
