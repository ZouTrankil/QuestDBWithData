package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.FileEvidenceStore;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MoneyflowThs;
import com.zoutrankil.data.domain.MoneyflowThsDataset;
import com.zoutrankil.data.domain.MoneyflowThsKey;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.repository.MoneyflowThsWritePort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** D025 per-date adapter; a late failed date leaves prior verified sessions resumable. */
public final class MoneyflowThsSyncAdapter implements SyncJobRunner.Adapter<MoneyflowThs, MoneyflowThsKey> {
    private static final int MAX_COMPLETION_BYTES = 16 * 1024 * 1024;
    private final MoneyflowThsSource source;
    private final ExchangeCalendarReadRepository calendars;
    private final MoneyflowThsWritePort port;
    private final Path evidenceRoot;

    public MoneyflowThsSyncAdapter(MoneyflowThsSource source, ExchangeCalendarReadRepository calendars,
            MoneyflowThsWritePort port, Path evidenceRoot) {
        this.source = Objects.requireNonNull(source); this.calendars = Objects.requireNonNull(calendars);
        this.port = Objects.requireNonNull(port); this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    @Override public void preflight(FrozenRequest request) {
        if (request == null || !MoneyflowThsSyncJobOwner.DEFINITION.equals(request.definition())
                || request.definition().datasetVersion() != MoneyflowThsDataset.DEFINITION.schemaVersion()
                || !Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE).contains(request.mode())
                || request.from() == null || request.to() == null || request.to().isAfter(request.logicalDate())
                || request.from().isAfter(request.to())
                || java.time.temporal.ChronoUnit.DAYS.between(request.from(), request.to()) + 1
                        > MoneyflowThsSyncJobOwner.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("Frozen bounded D025 request required");
        Map<String, Object> params = request.parameters();
        Set<String> allowed = Set.of("targetId", "checkpointAnchor", "checkpointBefore", "targetMinBefore", "targetMaxBefore");
        if (!params.keySet().contains("targetId") || !allowed.containsAll(params.keySet()))
            throw new IllegalArgumentException("Unexpected or missing D025 frozen parameters");
        if (!(params.get("targetId") instanceof String target) || !target.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen isolated D025 target identity required");
        Object anchor = params.get("checkpointAnchor"), before = params.get("checkpointBefore");
        if (request.mode() == Mode.INCREMENTAL) {
            if (!(anchor instanceof LocalDate date) || request.from().isBefore(date) || request.to().isBefore(date))
                throw new IllegalArgumentException("Incremental D025 request requires a valid frozen checkpoint anchor");
            if (before == null && !request.from().equals(date))
                throw new IllegalArgumentException("D025 bootstrap begins at its explicit anchor");
            if (before != null && (!(before instanceof LocalDate prior) || prior.isBefore(date)))
                throw new IllegalArgumentException("Invalid D025 prior checkpoint");
        } else if (anchor != null || before != null)
            throw new IllegalArgumentException("Only D025 incremental requests may carry checkpoint parameters");
        for (String field : List.of("targetMinBefore", "targetMaxBefore"))
            if (params.get(field) != null && !(params.get(field) instanceof LocalDate))
                throw new IllegalArgumentException("Invalid frozen D025 target range");
        if ((params.get("targetMinBefore") == null) != (params.get("targetMaxBefore") == null))
            throw new IllegalArgumentException("D025 physical target bounds must be paired");
        port.preflight();
    }

    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,
            SyncJobRunner.PageConsumer<MoneyflowThs> consumer, BooleanSupplier cancelled) throws Exception {
        var dates = DailyTradingSessions.read(calendars, request.from(), request.to());
        if (dates.isEmpty()) throw new IllegalArgumentException("D025 bounded run must include at least one verified SSE open date");
        int pageCount = 0, rowCount = 0;
        var pageEvidence = new ArrayList<Map<String, Object>>(dates.size());
        for (LocalDate date : dates) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new CancellationException("D025 cancelled before the next SSE date request");
            var page = source.fetch(date, cancelled);
            var expectedKeys = new HashSet<MoneyflowThsKey>();
            for (var row : page.rows()) if (!expectedKeys.add(row.key()))
                throw new IllegalStateException("D025 source page contains a duplicate full business key");
            for (var old : port.readDate(date)) if (!expectedKeys.contains(old.key()))
                throw new IllegalStateException("D025 source omitted an existing key; stale-row removal requires explicit publication handling");
            consumer.accept(page);
            var actual = port.readDate(date);
            if (!MoneyflowThsCoverage.sameRows(page.rows(), actual))
                throw new IllegalStateException("D025 whole-date QuestDB readback differs after the verified page write");
            pageCount++; rowCount = Math.addExact(rowCount, page.rows().size());
            pageEvidence.add(Map.of("tradeDate", date, "rows", page.rows().size(),
                    "sourceFingerprint", page.sourceFingerprint(), "responseEvidence", page.responseEvidence()));
        }
        Files.createDirectories(evidenceRoot);
        Path complete = evidenceRoot.resolve("complete-window.json");
        var body = new LinkedHashMap<String, Object>();
        body.put("jobId", MoneyflowThsSyncJobOwner.DEFINITION.jobId());
        body.put("jobVersion", MoneyflowThsSyncJobOwner.DEFINITION.version());
        body.put("datasetId", "moneyflow_ths"); body.put("mode", request.mode());
        body.put("targetId", request.parameters().get("targetId"));
        body.put("fromInclusive", request.from()); body.put("toInclusive", request.to());
        body.put("logicalDate", request.logicalDate());
        body.put("tradeDates", dates); body.put("completedDateSlices", pageCount); body.put("sourceRows", rowCount);
        body.put("sourceReceipts", pageEvidence); body.put("complete", true);
        byte[] bytes = JobDefinitionJson.canonicalMapper()
                .writeValueAsBytes(body);
        if (bytes.length > MAX_COMPLETION_BYTES) throw new IllegalStateException("D025 completion evidence exceeds 16 MiB");
        try { FileEvidenceStore.writeNew(complete,bytes); }
        catch (java.nio.file.FileAlreadyExistsException exists) { throw new IllegalStateException("D025 completion evidence collision", exists); }
        return new SyncJobRunner.SourceCompletion(pageCount, rowCount, true, complete.toString());
    }

    @Override public VerifiedBatchExecutor.Codec<MoneyflowThs, MoneyflowThsKey> codec() { return MoneyflowThsWritePort.CODEC; }
    @Override public VerifiedBatchExecutor.Port<MoneyflowThs, MoneyflowThsKey> port() { return port; }
}
