package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.port.StockDateWriteSession;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Executes exactly the frozen sequence of one-date all-market requests. */
public final class StockLimitSyncAdapter implements SyncJobRunner.Adapter<StockLimit, StockLimitKey> {
    private final StockLimitSource source;
    private final StockLimitTradingDates tradingDates;
    private final StockDateWriteSession<StockLimit, StockLimitKey> port;
    private final Path evidenceRoot;

    public StockLimitSyncAdapter(StockLimitSource source, StockLimitTradingDates tradingDates,
            StockDateWriteSession<StockLimit, StockLimitKey> port, Path evidenceRoot) {
        this.source = Objects.requireNonNull(source); this.tradingDates = Objects.requireNonNull(tradingDates);
        this.port = Objects.requireNonNull(port); this.evidenceRoot = evidenceRoot.toAbsolutePath().normalize();
    }

    public static String encodeTradeDates(List<LocalDate> dates) {
        if (dates == null || dates.size() > StockLimitSyncJobOwner.MAX_WINDOW_DAYS
                || dates.stream().distinct().count() != dates.size() || !dates.equals(dates.stream().sorted().toList()))
            throw new IllegalArgumentException("Unique ascending bounded stk_limit dates required");
        String encoded = dates.isEmpty() ? "NONE" : String.join(",", dates.stream()
                .map(date -> date.format(DateTimeFormatter.BASIC_ISO_DATE)).toList());
        if (encoded.length() > 4000) throw new IllegalArgumentException("stk_limit frozen date list exceeds 4000 characters");
        return encoded;
    }

    public static List<LocalDate> decodeTradeDates(FrozenRequest request) {
        Object value = request.parameters().get("trade_dates");
        if (!(value instanceof String encoded)) throw new IllegalArgumentException("Frozen stk_limit calendar text required");
        if (encoded.equals("NONE")) return List.of();
        if (encoded.isBlank()) throw new IllegalArgumentException("Empty stk_limit date marker is invalid");
        List<LocalDate> dates = Arrays.stream(encoded.split(",", -1)).map(date -> {
            if (!date.matches("[0-9]{8}")) throw new IllegalArgumentException("BASIC_ISO_DATE required");
            return LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
        }).toList();
        if (dates.size() > StockLimitSyncJobOwner.MAX_WINDOW_DAYS
                || dates.stream().distinct().count() != dates.size() || !dates.equals(dates.stream().sorted().toList())
                || dates.stream().anyMatch(date -> date.isBefore(request.from()) || date.isAfter(request.to())))
            throw new IllegalArgumentException("Frozen stk_limit dates are duplicate, unordered or outside the window");
        return dates;
    }

    @Override public void preflight(FrozenRequest request) {
        if (request == null || !request.definition().equals(StockLimitSyncJobOwner.DEFINITION)
                || !request.definition().datasetId().equals(StockLimitDataset.DEFINITION.datasetId())
                || request.definition().datasetVersion() != StockLimitDataset.DEFINITION.schemaVersion()
                || !Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE).contains(request.mode())
                || request.from() == null || request.to() == null || request.to().isAfter(request.logicalDate()))
            throw new IllegalArgumentException("Frozen bounded stk_limit request required");
        var params = request.parameters();
        Set<String> allowed = Set.of("targetId", "trade_dates", "checkpointAnchor", "checkpointBefore",
                "targetMinBefore", "targetMaxBefore");
        if (!params.keySet().containsAll(Set.of("targetId", "trade_dates")) || !allowed.containsAll(params.keySet()))
            throw new IllegalArgumentException("Unexpected or missing stk_limit frozen parameters");
        Object target = params.get("targetId");
        if (!(target instanceof String id) || !id.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen stk_limit target identity required");
        Object anchor = params.get("checkpointAnchor");
        if (request.mode() == Mode.INCREMENTAL) {
            if (!(anchor instanceof LocalDate date) || date.isAfter(request.to()) || request.from().isBefore(date))
                throw new IllegalArgumentException("INCREMENTAL stk_limit requires its bootstrap anchor");
        } else if (anchor != null || params.get("checkpointBefore") != null) {
            throw new IllegalArgumentException("Only INCREMENTAL stk_limit may carry checkpoint metadata");
        }
        for (String field : List.of("checkpointBefore", "targetMinBefore", "targetMaxBefore")) {
            Object date = params.get(field);
            if (date != null && !(date instanceof LocalDate)) throw new IllegalArgumentException("Invalid frozen date: " + field);
        }
        List<LocalDate> frozenDates = decodeTradeDates(request);
        if (!frozenDates.equals(tradingDates.read(request.from(), request.to())))
            throw new IllegalStateException("SSE calendar changed after stk_limit planning");
        port.preflight();
    }

    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,
            SyncJobRunner.PageConsumer<StockLimit> consumer, BooleanSupplier cancelled) throws Exception {
        var dates = decodeTradeDates(request); var evidence = new ArrayList<String>(); int rows = 0;
        for (var date : dates) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new CancellationException("stk_limit date slice cancelled");
            var page = source.fetch(date, cancelled);
            verifyNoSourceKeyRemoval(date, page.rows());
            consumer.accept(page);
            rows = Math.addExact(rows, page.rows().size()); evidence.add(page.responseEvidence());
        }
        Files.createDirectories(evidenceRoot);
        Path completion = evidenceRoot.resolve("complete-" + UUID.randomUUID() + ".json");
        JobDefinitionJson.canonicalMapper()
                .writeValue(completion.toFile(), Map.of("endpoint", "stk_limit", "from", request.from(), "to", request.to(),
                        "mode", request.mode(), "tradeDates", dates, "slices", dates.size(), "sourceRows", rows,
                        "sourceEvidence", evidence, "complete", true));
        if (Files.size(completion) > StockLimitSource.MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("stk_limit completion evidence exceeds 32 MiB budget");
        return new SyncJobRunner.SourceCompletion(dates.size(), rows, true, completion.toString());
    }
    private void verifyNoSourceKeyRemoval(LocalDate date, List<StockLimit> sourceRows) {
        var sourceKeys = new HashSet<StockLimitKey>();
        for (var row : sourceRows)
            if (!sourceKeys.add(row.key())) throw new IllegalStateException("Duplicate stk_limit source key");
        if (port.readDate(date).stream().anyMatch(row -> !sourceKeys.contains(row.key())))
            throw new IllegalStateException("stk_limit source omitted an existing target key; deletion requires an explicit staged replacement policy");
    }
    @Override public VerifiedBatchExecutor.Codec<StockLimit, StockLimitKey> codec() { return port.codec(); }
    @Override public VerifiedBatchExecutor.Port<StockLimit, StockLimitKey> port() { return port; }
}
