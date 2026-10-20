package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.engine.EtfHistoryWindow;
import com.zoutrankil.data.factor.storage.QuestDbGlobalObservations;
import com.zoutrankil.data.repository.FileEvidenceStore;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Native stock/ETF composition corresponding to Python factor_sync, with final physical readback. */
public final class PostCloseFactorPipelineService {
    public record Request(
            LocalDate date, LocalDate etfHistoryFrom, String factors, String pools, Path root) {
        public Request {
            Objects.requireNonNull(date);
            Objects.requireNonNull(etfHistoryFrom);
            Objects.requireNonNull(root);
            if (etfHistoryFrom.isAfter(date)
                    || factors == null
                    || factors.isBlank()
                    || pools == null
                    || pools.isBlank())
                throw new IllegalArgumentException(
                        "Explicit date, ETF history, factors and pools required");
            EtfHistoryWindow.requireBounded(etfHistoryFrom, date);
        }
    }

    public record Result(Path receipt, Map<String, Object> state) {}

    private final FactorPipelineService stocks;
    private final EtfFactorPipelineService etfs;
    private final QuestDbGlobalObservations observations;

    public PostCloseFactorPipelineService(
            FactorPipelineService stocks,
            EtfFactorPipelineService etfs,
            QuestDbGlobalObservations observations) {
        this.stocks = Objects.requireNonNull(stocks);
        this.etfs = Objects.requireNonNull(etfs);
        this.observations = Objects.requireNonNull(observations);
    }

    public Result run(Request request) throws Exception {
        Path root = request.root().toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (var channel =
                        FileChannel.open(
                                root.resolve("post-close-factor.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null)
                throw new IllegalStateException("Post-close factor pipeline already running");
            Path execution = root.resolve(request.date() + "-" + UUID.randomUUID());
            Files.createDirectory(execution);
            Path receipt = execution.resolve("pipeline.json");
            var state = new LinkedHashMap<String, Object>();
            state.put("schema", "java.post_close_factor_pipeline.v1");
            state.put("date", request.date());
            state.put("etfHistoryFrom", request.etfHistoryFrom());
            state.put("factors", request.factors());
            state.put("pools", request.pools());
            state.put("sourceComplete", false);
            state.put("factorReady", false);
            state.put("stockRoot", root.resolve("stock").toString());
            state.put("etfRoot", root.resolve("etf").toString());
            state.put("startedAt", Instant.now());
            state.put("physicalVisibilityVerified", false);
            var timings = new LinkedHashMap<String, Double>();
            state.put("stageMillis", timings);
            long start = System.nanoTime();
            try {
                state.put("phase", "STOCK_PUBLICATION");
                save(receipt, state);
                long stage = System.nanoTime();
                var stock =
                        stocks.run(
                                new FactorPipelineService.Request(
                                        request.date(),
                                        request.factors(),
                                        request.pools(),
                                        root.resolve("stock")));
                state.put("stockReceipt", stock.receipt().toString());
                timings.put("stock", millis(stage));
                if (!(stock.state().get("observationRows") instanceof Number expected)
                        || !(stock.state().get("verifiedRows") instanceof Number verified)
                        || expected.longValue() < 1
                        || expected.longValue() != verified.longValue())
                    throw new IllegalStateException(
                            "Stock publication did not verify all requested observations");
                state.put("phase", "ETF_PUBLICATION");
                save(receipt, state);
                stage = System.nanoTime();
                var etf =
                        etfs.run(
                                new EtfFactorPipelineService.Request(
                                        request.date(),
                                        request.etfHistoryFrom(),
                                        root.resolve("etf")));
                state.put("etfReceipt", etf.receipt().toString());
                timings.put("etf", millis(stage));
                if (!etf.observationsVerified())
                    throw new IllegalStateException(
                            "ETF publication incomplete; inspect child receipt");
                state.put("phase", "PHYSICAL_VISIBILITY");
                save(receipt, state);
                stage = System.nanoTime();
                Path bundlePath = stock.receipt().getParent().resolve("observations");
                var bundle = FactorObservationBundle.read(bundlePath);
                if (!bundle.sha256().equals(stock.state().get("observationBundleSha256"))
                        || !bundle.dates().equals(Set.of(request.date())))
                    throw new IllegalStateException("Stock output identity changed");
                var before = observations.state();
                long stockRows = 0;
                for (var part : bundle.manifest().parts())
                    stockRows +=
                            verify(bundlePath.resolve(part.file()), part.sha256(), request.date());
                Path etfPath = etf.receipt().getParent().resolve("observations.json");
                long etfRows =
                        verify(etfPath, (String) etf.state().get("evidenceSha256"), request.date());
                if (stockRows != expected.longValue()
                        || !(etf.state().get("expectedRows") instanceof Number etfExpected)
                        || etfRows != etfExpected.longValue()
                        || !before.equals(observations.state()))
                    throw new IllegalStateException(
                            "Combined output count or physical generation changed; retry readback");
                state.put("physicalState", before);
                state.put("stockRows", stockRows);
                state.put("etfRows", etfRows);
                state.put("physicalVisibilityVerified", true);
                timings.put("visibility", millis(stage));
                state.put("visibilityVerifiedAt", Instant.now());
                // Physical facts may be published with incomplete upstream history; do not mint
                // FactorReady.
                state.put("phase", "PARTIAL");
                state.put("incompleteReasons", List.of("SOURCE_NOT_CERTIFIED_COMPLETE"));
                timings.put("total", millis(start));
                save(receipt, state);
                return new Result(receipt, Collections.unmodifiableMap(new LinkedHashMap<>(state)));
            } catch (Exception failure) {
                state.put("interruptedPhase", state.get("phase"));
                state.put("phase", "FAILED_REQUIRES_INSPECTION");
                state.put("failureType", failure.getClass().getName());
                timings.put("total", millis(start));
                save(receipt, state);
                throw failure;
            }
        }
    }

    private long verify(Path path, String sha256, LocalDate date) throws Exception {
        var evidence = FactorObservationEvidence.read(path);
        if (!evidence.fileSha256().equals(sha256)
                || evidence.rows().isEmpty()
                || evidence.rows().stream().anyMatch(row -> !date.equals(row.signalDate())))
            throw new IllegalStateException("Frozen observations changed before final readback");
        for (int start = 0; start < evidence.rows().size(); start += 2000) {
            var page =
                    evidence.rows().subList(start, Math.min(start + 2000, evidence.rows().size()));
            var delta =
                    GlobalObservationCoverage.compare(
                            page,
                            observations.readKeys(
                                    page.stream().map(GlobalObservationCoverage::key).toList()));
            if (!delta.matchesExpected() || delta.unexpected() != 0)
                throw new IllegalStateException(
                        "Combined stock/ETF observations are not physically visible with expected values");
        }
        return evidence.rows().size();
    }

    private static double millis(long start) {
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static void save(Path path, Map<String, Object> state) throws Exception {
        FileEvidenceStore.replaceDurable(path, JobDefinitionJson.mapper().writeValueAsBytes(state));
    }
}
