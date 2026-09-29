package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.EtfDailyWritePort;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** Executes exactly the frozen sequence of one-date all-market requests. */
public final class EtfDailySyncAdapter implements SyncJobRunner.Adapter<EtfDaily, EtfDailyKey> {
    private final EtfDailySource source;
    private final EtfDailyTradingDates tradingDates;
    private final EtfDailyWritePort port;
    private final Path evidenceRoot;

    public EtfDailySyncAdapter(EtfDailySource source, EtfDailyTradingDates tradingDates,
            EtfDailyWritePort port, Path evidenceRoot) {
        this.source = Objects.requireNonNull(source); this.tradingDates = Objects.requireNonNull(tradingDates);
        this.port = Objects.requireNonNull(port); this.evidenceRoot = evidenceRoot.toAbsolutePath().normalize();
    }

    public static String encodeTradeDates(List<LocalDate> dates) {
        if (dates == null || dates.size() > EtfDailySyncJobOwner.MAX_WINDOW_DAYS
                || dates.stream().distinct().count() != dates.size() || !dates.equals(dates.stream().sorted().toList()))
            throw new IllegalArgumentException("Unique ascending bounded etf_daily dates required");
        String encoded = dates.isEmpty() ? "NONE" : String.join(",", dates.stream()
                .map(date -> date.format(DateTimeFormatter.BASIC_ISO_DATE)).toList());
        if (encoded.length() > 4000) throw new IllegalArgumentException("etf_daily frozen date list exceeds 4000 characters");
        return encoded;
    }

    public static List<LocalDate> decodeTradeDates(FrozenRequest request) {
        Object value = request.parameters().get("trade_dates");
        if (!(value instanceof String encoded)) throw new IllegalArgumentException("Frozen etf_daily calendar text required");
        if (encoded.equals("NONE")) return List.of();
        if (encoded.isBlank()) throw new IllegalArgumentException("Empty etf_daily date marker is invalid");
        List<LocalDate> dates = Arrays.stream(encoded.split(",", -1)).map(date -> {
            if (!date.matches("[0-9]{8}")) throw new IllegalArgumentException("BASIC_ISO_DATE required");
            return LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
        }).toList();
        if (dates.size() > EtfDailySyncJobOwner.MAX_WINDOW_DAYS
                || dates.stream().distinct().count() != dates.size() || !dates.equals(dates.stream().sorted().toList())
                || dates.stream().anyMatch(date -> date.isBefore(request.from()) || date.isAfter(request.to())))
            throw new IllegalArgumentException("Frozen etf_daily dates are duplicate, unordered or outside the window");
        return dates;
    }

    @Override public void preflight(FrozenRequest request) {
        if (request == null || !request.definition().equals(EtfDailySyncJobOwner.DEFINITION)
                || !request.definition().datasetId().equals(EtfDailyDataset.DEFINITION.datasetId())
                || request.definition().datasetVersion() != EtfDailyDataset.DEFINITION.schemaVersion()
                || !Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE).contains(request.mode())
                || request.from() == null || request.to() == null || request.to().isAfter(request.logicalDate()))
            throw new IllegalArgumentException("Frozen bounded etf_daily request required");
        var params = request.parameters();
        Set<String> allowed = Set.of("targetId", "trade_dates", "checkpointAnchor", "checkpointBefore",
                "targetMinBefore", "targetMaxBefore");
        if (!params.keySet().containsAll(Set.of("targetId", "trade_dates")) || !allowed.containsAll(params.keySet()))
            throw new IllegalArgumentException("Unexpected or missing etf_daily frozen parameters");
        Object target = params.get("targetId");
        if (!(target instanceof String id) || !id.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen etf_daily target identity required");
        Object anchor = params.get("checkpointAnchor");
        if (request.mode() == Mode.INCREMENTAL) {
            if (!(anchor instanceof LocalDate date) || date.isAfter(request.to()) || request.from().isBefore(date))
                throw new IllegalArgumentException("INCREMENTAL etf_daily requires its bootstrap anchor");
        } else if (anchor != null || params.get("checkpointBefore") != null) {
            throw new IllegalArgumentException("Only INCREMENTAL etf_daily may carry checkpoint metadata");
        }
        for (String field : List.of("checkpointBefore", "targetMinBefore", "targetMaxBefore")) {
            Object date = params.get(field);
            if (date != null && !(date instanceof LocalDate)) throw new IllegalArgumentException("Invalid frozen date: " + field);
        }
        List<LocalDate> frozenDates = decodeTradeDates(request);
        if (!frozenDates.equals(tradingDates.read(request.from(), request.to())))
            throw new IllegalStateException("SSE calendar changed after etf_daily planning");
        port.preflight();
    }

    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,
            SyncJobRunner.PageConsumer<EtfDaily> consumer, BooleanSupplier cancelled) throws Exception {
        var dates = decodeTradeDates(request); var evidence = new ArrayList<String>(); int rows = 0;
        for (var date : dates) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new CancellationException("etf_daily date slice cancelled");
            var page = source.fetch(date, cancelled); consumer.accept(page);
            rows = Math.addExact(rows, page.rows().size()); evidence.add(page.responseEvidence());
        }
        Files.createDirectories(evidenceRoot);
        Path completion = evidenceRoot.resolve("complete-" + UUID.randomUUID() + ".json");
        JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValue(completion.toFile(), Map.of("endpoint", "fund_daily", "from", request.from(), "to", request.to(),
                        "mode", request.mode(), "tradeDates", dates, "slices", dates.size(), "sourceRows", rows,
                        "sourceEvidence", evidence, "complete", true));
        if (Files.size(completion) > EtfDailySource.MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("etf_daily completion evidence exceeds 32 MiB budget");
        return new SyncJobRunner.SourceCompletion(dates.size(), rows, true, completion.toString());
    }
    @Override public VerifiedBatchExecutor.Codec<EtfDaily, EtfDailyKey> codec() { return EtfDailyWritePort.CODEC; }
    @Override public VerifiedBatchExecutor.Port<EtfDaily, EtfDailyKey> port() { return port; }
}
