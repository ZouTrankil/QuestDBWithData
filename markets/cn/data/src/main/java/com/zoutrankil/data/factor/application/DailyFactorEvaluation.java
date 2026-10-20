package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import com.zoutrankil.data.factor.engine.DailyFactorLabels;
import com.zoutrankil.data.factor.engine.FactorNeutralization;
import com.zoutrankil.data.factor.engine.FactorPreprocessing;
import com.zoutrankil.data.factor.engine.FrozenFactorSample;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Raw and pool-relative daily diagnostics with a shared label and exposure input. */
public final class DailyFactorEvaluation {
    private DailyFactorEvaluation() {}

    public record Request(FrozenFactorSample.Identity identity, List<String> members) {
        public Request {
            members = List.copyOf(members);
        }
    }

    public record Exposure(double logMarketCap, String industry) {}

    public record Evaluated(
            FrozenFactorSample.Sample raw,
            FrozenFactorSample.Sample effective,
            FactorPreprocessing.Result preprocessing) {}

    /** Reuse frozen labels; emit each pool/factor result without another source read.
     * The projection cache is shared across factors and closed after the invocation.
     */
    public static void evaluateEffective(
            List<FrozenFactorSample.Sample> rawSamples,
            FactorDefinitionCatalog catalog,
            Map<String, Exposure> exposures,
            Consumer<Evaluated> sink) {
        Objects.requireNonNull(sink);
        Objects.requireNonNull(exposures);
        try (var cache = new FactorNeutralization.ProjectionCache(64)) {
            for (var raw : rawSamples) {
                sink.accept(evaluateEffectiveSample(raw, catalog, exposures, cache));
            }
        }
    }

    /** Keep only one pool/factor sample alive while sharing labels, sources and projection cache.
     * The sink may retain results, but doing so forfeits the bounded intermediate-memory benefit.
     */
    public static void evaluateStreaming(
            LocalDate signal,
            LocalDate maturity,
            String labelDefinition,
            List<DailyFactorLabels.Price> prices,
            List<DailyFactorLabels.Suspension> suspensions,
            List<GlobalFactorObservation> observations,
            List<Request> requests,
            FactorDefinitionCatalog catalog,
            Map<String, Exposure> exposures,
            Consumer<Evaluated> sink) {
        Objects.requireNonNull(sink);
        Objects.requireNonNull(exposures);
        Objects.requireNonNull(catalog);
        try (var cache = new FactorNeutralization.ProjectionCache(64)) {
            evaluateRaw(
                    signal,
                    maturity,
                    labelDefinition,
                    prices,
                    suspensions,
                    observations,
                    requests,
                    raw -> sink.accept(evaluateEffectiveSample(raw, catalog, exposures, cache)));
        }
    }

    private static Evaluated evaluateEffectiveSample(
            FrozenFactorSample.Sample raw,
            FactorDefinitionCatalog catalog,
            Map<String, Exposure> exposures,
            FactorNeutralization.ProjectionCache cache) {
        var identity = raw.identity();
        var definition = catalog.require(identity.factorId());
        if (!definition.version().equals(identity.definitionVersion())
                || !definition.assetType().equals("stock"))
            throw new IllegalArgumentException(
                    "Effective factor definition differs from frozen sample");
        int n = raw.members().size();
        String[] symbols = new String[n], industries = new String[n];
        double[] values = new double[n], sizes = new double[n];
        var labels = new ArrayList<DailyFactorLabels.Label>(n);
        for (int i = 0; i < n; i++) {
            var member = raw.members().get(i);
            symbols[i] = member.symbol();
            values[i] = member.factor();
            var exposure = exposures.get(member.symbol());
            sizes[i] = exposure == null ? Double.NaN : exposure.logMarketCap();
            industries[i] = exposure == null ? null : exposure.industry();
            labels.add(
                    new DailyFactorLabels.Label(
                            member.symbol(),
                            identity.signalDate(),
                            Double.NaN,
                            Double.NaN,
                            null,
                            "frozen_evaluation_return",
                            member.forwardReturn()));
        }
        var processed =
                FactorPreprocessing.compute(definition, symbols, values, sizes, industries, cache);
        var effective = new ArrayList<FrozenFactorSample.Observation>(n);
        for (int i = 0; i < n; i++)
            effective.add(
                    new FrozenFactorSample.Observation(
                            identity.signalDate(),
                            symbols[i],
                            definition.factorId(),
                            definition.version(),
                            processed.effective()[i]));
        var sample = FrozenFactorSample.freeze(identity, List.of(symbols), effective, labels);
        return new Evaluated(raw, sample, processed);
    }

    public static List<FrozenFactorSample.Sample> evaluateRaw(
            LocalDate signal,
            LocalDate maturity,
            String labelDefinition,
            List<DailyFactorLabels.Price> prices,
            List<DailyFactorLabels.Suspension> suspensions,
            List<GlobalFactorObservation> observations,
            List<Request> requests) {
        var results = new ArrayList<FrozenFactorSample.Sample>();
        evaluateRaw(
                signal,
                maturity,
                labelDefinition,
                prices,
                suspensions,
                observations,
                requests,
                results::add);
        return List.copyOf(results);
    }

    private static void evaluateRaw(
            LocalDate signal,
            LocalDate maturity,
            String labelDefinition,
            List<DailyFactorLabels.Price> prices,
            List<DailyFactorLabels.Suspension> suspensions,
            List<GlobalFactorObservation> observations,
            List<Request> requests,
            Consumer<FrozenFactorSample.Sample> sink) {
        if (observations.size() > 2_000_000 || requests.isEmpty() || requests.size() > 10_000)
            throw new IllegalArgumentException("Bounded explicit daily evaluation required");
        var keys = new HashSet<GlobalObservationCoverage.Key>();
        var selected = new HashMap<String, List<FrozenFactorSample.Observation>>();
        var requestedFactors = new HashSet<String>();
        for (var request : requests) requestedFactors.add(request.identity().factorId());
        var cutoff = signal.plusDays(1).atStartOfDay().minusNanos(1000);
        // Match the Python admission order: reject duplicate input business keys,
        // including rows subsequently excluded by scope or availability.
        for (var row : observations) {
            if (!keys.add(GlobalObservationCoverage.key(row)))
                throw new IllegalArgumentException("Duplicate observation business key");
            if (!row.entityType().equals("stock")
                    || !row.scope().equals("GLOBAL")
                    || !row.signalDate().equals(signal)
                    || row.availableAt().isAfter(cutoff)
                    || !requestedFactors.contains(row.factorId())) continue;
            selected.computeIfAbsent(row.factorId(), ignored -> new ArrayList<>())
                    .add(
                            new FrozenFactorSample.Observation(
                                    row.signalDate(),
                                    row.entityId(),
                                    row.factorId(),
                                    row.definitionVersion(),
                                    row.factorValue() == null ? Double.NaN : row.factorValue()));
        }
        var distinct = new HashSet<FrozenFactorSample.Identity>();
        for (var request : requests) {
            if (!request.identity().signalDate().equals(signal)
                    || !request.identity().labelDefinition().equals(labelDefinition)
                    || !distinct.add(request.identity()))
                throw new IllegalArgumentException("Mismatched or duplicate evaluation request");
        }
        var labels =
                DailyFactorLabels.build(signal, maturity, prices, suspensions, labelDefinition);
        for (var request : requests)
            sink.accept(
                    FrozenFactorSample.freeze(
                            request.identity(),
                            request.members(),
                            selected.getOrDefault(request.identity().factorId(), List.of()),
                            labels));
    }
}
