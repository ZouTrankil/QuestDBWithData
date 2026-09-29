package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode;
import com.zoutrankil.questdbwithdata.repository.EtfFactorWritePort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Executes the exact frozen calendar date sequence; formal data is never written by this owner. */
public final class EtfFactorSyncAdapter implements SyncJobRunner.Adapter<EtfFactor, EtfFactorKey> {
    private final EtfFactorSource source;
    private final EtfFactorTradingDates calendars;
    private final EtfFactorWritePort port;
    private final Path evidenceRoot;
    public EtfFactorSyncAdapter(EtfFactorSource source, EtfFactorTradingDates calendars,
            EtfFactorWritePort port, Path evidenceRoot) {
        this.source = Objects.requireNonNull(source); this.calendars = Objects.requireNonNull(calendars);
        this.port = Objects.requireNonNull(port); this.evidenceRoot = evidenceRoot.toAbsolutePath().normalize();
    }
    public static String encodeDates(List<LocalDate> dates) {
        if (dates == null || dates.size() > EtfFactorSyncJobOwner.MAX_WINDOW_DAYS
                || dates.stream().distinct().count() != dates.size() || !dates.equals(dates.stream().sorted().toList()))
            throw new IllegalArgumentException("Unique ascending bounded etf_factor trade dates required");
        String value = dates.isEmpty() ? "NONE" : String.join(",", dates.stream()
                .map(date -> date.format(DateTimeFormatter.BASIC_ISO_DATE)).toList());
        if (value.length() > 4000) throw new IllegalArgumentException("Frozen etf_factor date list exceeds limit");
        return value;
    }
    public static List<LocalDate> decodeDates(SyncJobDefinition.FrozenRequest request) {
        Object raw = request.parameters().get("trade_dates");
        if (!(raw instanceof String encoded)) throw new IllegalArgumentException("Frozen etf_factor calendar required");
        if (encoded.equals("NONE")) return List.of();
        if (encoded.isBlank()) throw new IllegalArgumentException("Empty etf_factor calendar marker is invalid");
        var dates = Arrays.stream(encoded.split(",", -1)).map(value -> {
            if (!value.matches("[0-9]{8}")) throw new IllegalArgumentException("BASIC_ISO_DATE required");
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
        }).toList();
        if (dates.size() > EtfFactorSyncJobOwner.MAX_WINDOW_DAYS || dates.stream().distinct().count() != dates.size()
                || !dates.equals(dates.stream().sorted().toList())
                || dates.stream().anyMatch(date -> date.isBefore(request.from()) || date.isAfter(request.to())))
            throw new IllegalArgumentException("Frozen etf_factor dates are duplicate, unordered or out of range");
        return dates;
    }
    @Override public void preflight(SyncJobDefinition.FrozenRequest request) {
        if (request == null || !request.definition().equals(EtfFactorSyncJobOwner.DEFINITION)
                || request.definition().datasetVersion() != EtfFactorDataset.DEFINITION.schemaVersion()
                || !Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE).contains(request.mode())
                || request.from() == null || request.to() == null || request.to().isAfter(request.logicalDate()))
            throw new IllegalArgumentException("Frozen bounded etf_factor request required");
        var params = request.parameters();
        Set<String> allowed = Set.of("targetId", "trade_dates", "checkpointAnchor", "checkpointBefore", "targetMinBefore", "targetMaxBefore");
        if (!params.keySet().containsAll(Set.of("targetId", "trade_dates")) || !allowed.containsAll(params.keySet()))
            throw new IllegalArgumentException("Unexpected or missing frozen etf_factor parameters");
        if (!(params.get("targetId") instanceof String target) || !target.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen etf_factor static target identity required");
        Object anchor = params.get("checkpointAnchor");
        if (request.mode() == Mode.INCREMENTAL) {
            if (!(anchor instanceof LocalDate date) || date.isAfter(request.to()) || request.from().isBefore(date))
                throw new IllegalArgumentException("Incremental etf_factor request requires its bootstrap anchor");
        } else if (anchor != null || params.get("checkpointBefore") != null)
            throw new IllegalArgumentException("Only incremental etf_factor plans carry checkpoint metadata");
        for (String name : List.of("checkpointBefore", "targetMinBefore", "targetMaxBefore"))
            if (params.get(name) != null && !(params.get(name) instanceof LocalDate))
                throw new IllegalArgumentException("Invalid frozen etf_factor date: " + name);
        var frozen = decodeDates(request);
        if (!frozen.equals(calendars.read(request.from(), request.to())))
            throw new IllegalStateException("D001 exchange calendar changed after etf_factor planning");
        port.preflight();
    }
    @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<EtfFactor> consumer, BooleanSupplier cancelled) throws Exception {
        var dates = decodeDates(request); var sources = new ArrayList<String>(); int rows = 0;
        for (var date : dates) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new CancellationException("fund_factor_pro trade-date slice cancelled");
            var page = source.fetch(date, cancelled);
            consumer.accept(page); // Empty is a normal, receipt-backed daily source result.
            rows = Math.addExact(rows, page.rows().size()); sources.add(page.responseEvidence());
        }
        Files.createDirectories(evidenceRoot);
        Path complete = evidenceRoot.resolve("complete-" + UUID.randomUUID() + ".json");
        var body = new java.util.LinkedHashMap<String,Object>();
        body.put("endpoint", "fund_factor_pro"); body.put("from", request.from()); body.put("to", request.to());
        body.put("mode", request.mode()); body.put("tradeDates", dates); body.put("slices", dates.size());
        body.put("sourceRows", rows); body.put("sourceEvidence", sources); body.put("complete", true);
        JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).writeValue(complete.toFile(), body);
        if (Files.size(complete) > EtfFactorSource.MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("etf_factor completion receipt exceeds 32 MiB");
        return new SyncJobRunner.SourceCompletion(dates.size(), rows, true, complete.toString());
    }
    @Override public VerifiedBatchExecutor.Codec<EtfFactor, EtfFactorKey> codec() { return EtfFactorWritePort.CODEC; }
    @Override public VerifiedBatchExecutor.Port<EtfFactor, EtfFactorKey> port() { return port; }
}
