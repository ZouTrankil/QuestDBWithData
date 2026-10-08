package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.EtfPortfolio;
import com.zoutrankil.data.domain.EtfPortfolioDataset;
import com.zoutrankil.data.domain.EtfPortfolioKey;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.etf.port.EtfWriteSession;
import com.zoutrankil.data.service.VerifiedBatchExecutor.Codec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Executes a frozen sequence of one-calendar-day fund_portfolio requests with synchronous backpressure. */
public final class EtfPortfolioSyncAdapter implements SyncJobRunner.Adapter<EtfPortfolio, EtfPortfolioKey> {
    private static final int MAX_COMPLETION_BYTES = 32 * 1024 * 1024;
    private final EtfPortfolioSource source;
    private final EtfWriteSession<EtfPortfolio, EtfPortfolioKey> port;
    private final Path evidenceRoot;

    public EtfPortfolioSyncAdapter(EtfPortfolioSource source, EtfWriteSession<EtfPortfolio, EtfPortfolioKey> port, Path evidenceRoot) {
        this.source = Objects.requireNonNull(source); this.port = Objects.requireNonNull(port);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public static String encodeAnnouncementDates(List<LocalDate> dates) {
        if (dates == null || dates.isEmpty() || dates.size() > EtfPortfolioSyncJobOwner.MAX_WINDOW_DAYS
                || dates.stream().distinct().count() != dates.size() || !dates.equals(dates.stream().sorted().toList()))
            throw new IllegalArgumentException("Unique ascending bounded etf_portfolio announcement dates required");
        String encoded = String.join(",", dates.stream().map(date -> date.format(DateTimeFormatter.BASIC_ISO_DATE)).toList());
        if (encoded.length() > 404) throw new IllegalArgumentException("Frozen etf_portfolio date sequence exceeds 404 characters");
        return encoded;
    }

    public static List<LocalDate> decodeAnnouncementDates(FrozenRequest request) {
        Object value = request.parameters().get("ann_dates");
        if (!(value instanceof String encoded) || encoded.isBlank())
            throw new IllegalArgumentException("Frozen etf_portfolio announcement date sequence required");
        var dates = Arrays.stream(encoded.split(",", -1)).map(text -> {
            if (!text.matches("[0-9]{8}")) throw new IllegalArgumentException("BASIC_ISO_DATE required");
            return LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE);
        }).toList();
        if (dates.size() > EtfPortfolioSyncJobOwner.MAX_WINDOW_DAYS
                || dates.stream().distinct().count() != dates.size()
                || !dates.equals(dates.stream().sorted().toList())
                || dates.stream().anyMatch(date -> date.isBefore(request.from()) || date.isAfter(request.to()))
                || dates.size() != ChronoUnit.DAYS.between(request.from(), request.to()) + 1)
            throw new IllegalArgumentException("Frozen etf_portfolio dates are duplicate, unordered or incomplete");
        for (int i = 0; i < dates.size(); i++) if (!dates.get(i).equals(request.from().plusDays(i)))
            throw new IllegalArgumentException("Frozen etf_portfolio dates must include every calendar day");
        return dates;
    }

    public static Instant observedAt(FrozenRequest request) {
        validateShape(request);
        String text = (String) request.parameters().get("observedAt");
        Instant result;
        try { result = Instant.parse(text); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Canonical frozen etf_portfolio observation instant required", invalid); }
        com.zoutrankil.data.domain.temporal.TemporalValues.requirePrecision(result,
                com.zoutrankil.data.domain.temporal.TemporalValues.Precision.MICROS);
        if (!result.toString().equals(text)) throw new IllegalArgumentException("Canonical frozen etf_portfolio observation required");
        return result;
    }

    private static void validateShape(FrozenRequest request) {
        if (request == null || !request.definition().equals(EtfPortfolioSyncJobOwner.DEFINITION)
                || !request.definition().datasetId().equals(EtfPortfolioDataset.DEFINITION.datasetId())
                || request.definition().datasetVersion() != EtfPortfolioDataset.DEFINITION.schemaVersion()
                || !Set.of(Mode.INCREMENTAL, Mode.BACKFILL).contains(request.mode())
                || request.from() == null || request.to() == null || request.to().isAfter(request.logicalDate()))
            throw new IllegalArgumentException("Frozen bounded etf_portfolio request required");
        var params = request.parameters();
        Set<String> allowed = Set.of("targetId", "ann_dates", "observedAt", "checkpointAnchor", "checkpointBefore",
                "targetMinBefore", "targetMaxBefore");
        if (!params.keySet().containsAll(Set.of("targetId", "ann_dates", "observedAt")) || !allowed.containsAll(params.keySet()))
            throw new IllegalArgumentException("Unexpected/missing frozen etf_portfolio parameters");
        Object target = params.get("targetId");
        if (!(target instanceof String id) || !id.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen physical etf_portfolio target identity required");
        if (request.mode() == Mode.INCREMENTAL) {
            if (!(params.get("checkpointAnchor") instanceof LocalDate anchor) || anchor.isAfter(request.from())
                    || params.get("checkpointBefore") != null && !(params.get("checkpointBefore") instanceof LocalDate))
                throw new IllegalArgumentException("Incremental etf_portfolio requires frozen checkpoint anchor");
        } else if (params.containsKey("checkpointAnchor") || params.containsKey("checkpointBefore"))
            throw new IllegalArgumentException("Only incremental etf_portfolio may carry checkpoint metadata");
        for (String field : List.of("targetMinBefore", "targetMaxBefore"))
            if (params.get(field) != null && !(params.get(field) instanceof LocalDate))
                throw new IllegalArgumentException("Invalid frozen etf_portfolio baseline: " + field);
        if ((params.get("targetMinBefore") == null) != (params.get("targetMaxBefore") == null))
            throw new IllegalArgumentException("Both physical etf_portfolio baseline dates must be frozen together");
    }

    @Override public void preflight(FrozenRequest request) {
        validateShape(request); observedAt(request);
        decodeAnnouncementDates(request); port.preflight();
    }

    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,
            SyncJobRunner.PageConsumer<EtfPortfolio> consumer, BooleanSupplier cancelled) throws Exception {
        Instant observedAt = observedAt(request); var dates = decodeAnnouncementDates(request);
        var receipts = new ArrayList<Map<String, Object>>(); int rows = 0, pages = 0;
        for (LocalDate date : dates) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new CancellationException("etf_portfolio announcement-date slice cancelled");
            var result = source.fetch(date, observedAt, cancelled);
            var chunkEvidence = new ArrayList<Map<String, Object>>(result.chunks().size());
            for (var chunk : result.chunks()) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new CancellationException("etf_portfolio cancelled between runner chunks");
                consumer.accept(new SyncJobRunner.Page<>(chunk.rows(), chunk.fingerprint(),
                        chunk.responseEvidence(), chunk.cursor()));
                chunkEvidence.add(Map.of("index", chunk.index(), "count", chunk.count(), "offset", chunk.offset(),
                        "rows", chunk.rows().size(), "fingerprint", chunk.fingerprint(),
                        "responseEvidence", chunk.responseEvidence(), "cursor", chunk.cursor()));
                pages++;
            }
            List<EtfPortfolio> actual = port.readDate(date);
            if (!EtfPortfolioCoverage.sameRows(result.rows(), actual, port.codec()))
                throw new IllegalStateException("Physical etf_portfolio date differs from complete source receipt: " + date);
            rows = Math.addExact(rows, result.rows().size());
            receipts.add(Map.of("date", date, "rows", result.rows().size(), "fingerprint", result.fingerprint(),
                    "responseEvidence", result.receipt(), "sourcePages", result.sourcePages(),
                    "runnerChunks", chunkEvidence));
        }
        Files.createDirectories(evidenceRoot);
        Path complete = evidenceRoot.resolve("complete-" + UUID.randomUUID() + ".json");
        var body = new LinkedHashMap<String, Object>();
        body.put("endpoint", EtfPortfolioSource.ENDPOINT); body.put("sourceContractVersion", EtfPortfolioSource.CONTRACT_VERSION);
        body.put("mode", request.mode()); body.put("from", request.from()); body.put("to", request.to());
        body.put("observedAt", observedAt); body.put("announcementDates", dates); body.put("slices", dates.size());
        body.put("sourceRows", rows); body.put("receipts", receipts); body.put("complete", true);
        JobDefinitionJson.canonicalMapper()
                .writeValue(complete.toFile(), body);
        if (Files.size(complete) > MAX_COMPLETION_BYTES)
            throw new IllegalArgumentException("etf_portfolio completion evidence exceeds 32 MiB");
        return new SyncJobRunner.SourceCompletion(pages, rows, true, complete.toString());
    }

    @Override public Codec<EtfPortfolio, EtfPortfolioKey> codec() { return port.codec(); }
    @Override public VerifiedBatchExecutor.Port<EtfPortfolio, EtfPortfolioKey> port() { return port; }
}
