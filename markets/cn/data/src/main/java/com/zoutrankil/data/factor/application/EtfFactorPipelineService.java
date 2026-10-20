package com.zoutrankil.data.factor.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.temporal.MarketId;
import com.zoutrankil.data.domain.temporal.MarketTime;
import com.zoutrankil.data.factor.application.EtfPipelineReuse.Reused;
import com.zoutrankil.data.factor.engine.EtfHistoryWindow;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.market.cn.time.CnMarketTime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Finite native ETF computation, incremental publication and explicit frozen-output recovery. */
public final class EtfFactorPipelineService {
    public record Request(LocalDate date, LocalDate historyFrom, Path root) {
        public Request {
            Objects.requireNonNull(date);
            Objects.requireNonNull(historyFrom);
            Objects.requireNonNull(root);
            EtfHistoryWindow.requireBounded(historyFrom, date);
        }
    }

    public record Result(Path receipt, Map<String, Object> state) {
        /** Physical publication completion is separate from source completeness / FactorReady. */
        public boolean observationsVerified() {
            return state.get("publication") instanceof SyncJobRunner.Result result
                    && (result.state() == SyncRunState.PARTIAL
                            || result.state() == SyncRunState.VERIFIED)
                    && state.get("expectedRows") instanceof Number expected
                    && result.verifiedRows() == expected.longValue();
        }
    }

    private final MarketTime marketTime;
    private final EtfFactorPanelComputation panels;
    private final FactorObservationPublicationService publication;

    public EtfFactorPipelineService(
            EtfFactorPanelComputation panels, FactorObservationPublicationService publication) {
        this(panels, publication, CnMarketTime.system());
    }

    public EtfFactorPipelineService(
            EtfFactorPanelComputation panels,
            FactorObservationPublicationService publication,
            MarketTime marketTime) {
        this.marketTime = Objects.requireNonNull(marketTime).requireMarket(MarketId.CN);
        this.panels = Objects.requireNonNull(panels);
        this.publication = Objects.requireNonNull(publication);
    }

    public Result run(Request request) throws Exception {
        var date = request.date();
        var history = request.historyFrom();
        var now = marketTime.inBusinessZone(marketTime.now()).toLocalDateTime();
        if (history.isAfter(date) || date.isAfter(now.toLocalDate()))
            throw new IllegalArgumentException("Invalid ETF pipeline dates");
        var root = request.root().toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (var channel =
                        FileChannel.open(
                                root.resolve("etf-pipeline.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("ETF pipeline already running");
            var run = root.resolve(date + "-" + UUID.randomUUID());
            Files.createDirectory(run);
            var receipt = run.resolve("pipeline.json");
            var panel = run.resolve("panel.json");
            var evidence = run.resolve("observations.json");
            var state = new LinkedHashMap<String, Object>();
            state.put("schema", "java.etf_pipeline.v1");
            state.put("date", date.toString());
            state.put("historyFrom", history.toString());
            state.put("sourceComplete", false);
            state.put("factorReady", false);
            state.put("phase", "COMPUTING");
            save(receipt, state);
            try {
                var catalog = FactorDefinitionCatalog.load();
                String kernel = FactorComputationIdentity.sha256(EtfFactorPanelComputation.class);
                String aliases = EtfPipelineReuse.aliasSha256();
                var reused = tryReuse(root, run, date, history, kernel, catalog, aliases);
                state.put("computationReused", reused.isPresent());
                state.put("kernelSha256", kernel);
                state.put("catalogSha256", catalog.sourceConfigSha256());
                state.put("aliasRulesSha256", aliases);
                if (reused.isPresent()) {
                    state.put("reusedExecutionDirectory", reused.get().executionDirectory());
                    state.put("computedAt", reused.get().computedAt());
                    state.put("panelSha256", reused.get().panelSha256());
                } else {
                    panels.compute(
                            new EtfFactorPanelComputation.Request(history, date, date, panel));
                    state.put(
                            "panelSha256",
                            FileEvidenceStore.sha256(
                                    FileEvidenceStore.readBounded(
                                            panel,
                                            64 * 1024 * 1024,
                                            () ->
                                                    new IllegalArgumentException(
                                                            "ETF panel exceeds reuse budget"))));
                    String computedAt =
                            marketTime
                                    .inBusinessZone(marketTime.now())
                                    .toLocalDateTime()
                                    .toString();
                    state.put("computedAt", computedAt);
                    state.put("phase", "EXPORTING");
                    save(receipt, state);
                    panels.exportObservations(
                            new EtfFactorPanelComputation.ExportRequest(
                                    panel, evidence, LocalDateTime.parse(computedAt), List.of()));
                }
                var frozen = FactorObservationEvidence.read(evidence);
                state.put("expectedRows", frozen.rows().size());
                state.put("evidenceSha256", frozen.fileSha256());
                var runId = "factor-observation-" + UUID.randomUUID();
                state.put("publicationRunId", runId);
                state.put("phase", "PUBLISHING");
                save(receipt, state);
                var result =
                        publication.publishEvidence(
                                evidence, date, SyncJobDefinition.Mode.INCREMENTAL, null, runId);
                state.put("publication", result);
                state.put("phase", result.state().name());
                save(receipt, state);
                return new Result(receipt, Collections.unmodifiableMap(new LinkedHashMap<>(state)));
            } catch (Exception failure) {
                state.put("interruptedPhase", state.get("phase"));
                state.put("phase", "FAILED_REQUIRES_INSPECTION");
                state.put("failureType", failure.getClass().getName());
                save(receipt, state);
                throw failure;
            }
        }
    }

    /** Resume the exact frozen output, without recomputing against newer source data. */
    public Result resume(Path executionDirectory) throws Exception {
        var previous = Objects.requireNonNull(executionDirectory).toAbsolutePath().normalize();
        var root = Objects.requireNonNull(previous.getParent(), "Pipeline root required");
        try (var channel =
                        FileChannel.open(
                                root.resolve("etf-pipeline.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("ETF pipeline already running");
            var json = JobDefinitionJson.mapper();
            var old =
                    json.readTree(
                            FileEvidenceStore.readBounded(
                                    previous.resolve("pipeline.json"),
                                    1024 * 1024,
                                    () -> new IllegalArgumentException("Oversized ETF receipt")));
            if (!"java.etf_pipeline.v1".equals(old.path("schema").asText()))
                throw new IllegalArgumentException("ETF pipeline receipt required");
            String prior = old.path("publicationRunId").asText();
            if (!prior.startsWith("factor-observation-"))
                throw new IllegalArgumentException("No publication to resume");
            var date = LocalDate.parse(old.path("date").asText());
            var source = previous.resolve("observations.json");
            var frozen = FactorObservationEvidence.read(source);
            if (!frozen.fileSha256().equals(old.path("evidenceSha256").asText())
                    || frozen.rows().size() != old.path("expectedRows").asInt(-1)
                    || frozen.rows().stream().anyMatch(row -> !date.equals(row.signalDate())))
                throw new IllegalArgumentException("ETF resume evidence differs from receipt");
            var bytes =
                    FileEvidenceStore.readBounded(
                            source,
                            32 * 1024 * 1024,
                            () -> new IllegalArgumentException("Oversized ETF evidence"));
            if (!frozen.fileSha256().equals(FileEvidenceStore.sha256(bytes)))
                throw new IllegalArgumentException("ETF evidence changed during resume");
            publication.requireResumableEvidence(source, date, prior);
            var run = root.resolve(date + "-" + UUID.randomUUID());
            Files.createDirectory(run);
            var evidence = run.resolve("observations.json");
            FileEvidenceStore.writeNewDurable(evidence, bytes);
            var receipt = run.resolve("pipeline.json");
            var state = new LinkedHashMap<String, Object>();
            state.put("schema", "java.etf_pipeline.v1");
            state.put("date", date.toString());
            state.put("historyFrom", old.path("historyFrom").asText());
            state.put("resumedFrom", previous.toString());
            state.put("priorPublicationRunId", prior);
            state.put("sourceComplete", false);
            state.put("factorReady", false);
            state.put("expectedRows", frozen.rows().size());
            state.put("evidenceSha256", frozen.fileSha256());
            String runId = "factor-observation-" + UUID.randomUUID();
            state.put("publicationRunId", runId);
            state.put("phase", "PUBLISHING");
            save(receipt, state);
            try {
                var result =
                        publication.publishEvidence(
                                evidence, date, SyncJobDefinition.Mode.INCREMENTAL, prior, runId);
                state.put("publication", result);
                state.put("phase", result.state().name());
                save(receipt, state);
                return new Result(receipt, Collections.unmodifiableMap(new LinkedHashMap<>(state)));
            } catch (Exception failure) {
                state.put("interruptedPhase", state.get("phase"));
                state.put("phase", "FAILED_REQUIRES_INSPECTION");
                state.put("failureType", failure.getClass().getName());
                save(receipt, state);
                throw failure;
            }
        }
    }

    private static void save(Path path, Map<String, Object> state) throws Exception {
        FileEvidenceStore.replaceDurable(path, JobDefinitionJson.mapper().writeValueAsBytes(state));
    }

    private Optional<Reused> tryReuse(
            Path root,
            Path current,
            LocalDate date,
            LocalDate history,
            String kernel,
            FactorDefinitionCatalog catalog,
            String aliases)
            throws Exception {
        List<Path> candidates;
        try (var paths = Files.list(root)) {
            candidates =
                    paths.filter(
                                    p ->
                                            !p.equals(current)
                                                    && p.getFileName()
                                                            .toString()
                                                            .startsWith(date + "-")
                                                    && Files.isDirectory(
                                                            p, LinkOption.NOFOLLOW_LINKS))
                            .sorted(
                                    Comparator.comparingLong(EtfFactorPipelineService::modified)
                                            .reversed())
                            .limit(32)
                            .toList();
        }
        for (var candidate : candidates)
            try {
                var result =
                        EtfPipelineReuse.copyIfCurrent(
                                candidate,
                                current,
                                date,
                                history,
                                kernel,
                                catalog,
                                aliases,
                                panels::requireSourceStateUnchanged);
                if (result.isPresent()) return result;
            } catch (NoSuchFileException
                    | IllegalArgumentException
                    | IllegalStateException
                    | JsonProcessingException stale) {
                // A stale or incomplete calculation is recomputed; publication never trusts a
                // receipt alone.
            }
        return Optional.empty();
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException missing) {
            return 0;
        }
    }
}
