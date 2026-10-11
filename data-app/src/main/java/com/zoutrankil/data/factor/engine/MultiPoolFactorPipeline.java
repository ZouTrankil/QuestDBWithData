package com.zoutrankil.data.factor.engine;

import com.zoutrankil.data.factor.domain.FactorDefinition;
import com.zoutrankil.data.factor.application.FactorIncrementalPlanner;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Sequential, one-date pipeline. Raw columns are borrowed read-only for the call.
 * The caller freezes PIT membership and source snapshots before invoking this stage.
 * Results are emitted factor by factor, avoiding a materialized factor x pool table.
 */
public final class MultiPoolFactorPipeline {
    private MultiPoolFactorPipeline() {}

    public record RawFactor(FactorDefinition definition, double[] values) {}
    public record Pool(String id, int[] memberIndices) {}
    public record Output(LocalDate date, String poolId, FactorDefinition definition,
            List<String> symbols, FactorPreprocessing.Result values) {}
    public record Stats(long emittedSections, long projectionBuilds, long projectionHits) {}

    /** Resolve the shared PIT exposure panel once, then borrow it for every pool and factor. */
    public static Stats computeWithMembership(LocalDate date, Set<LocalDate> outputDates,
            String[] symbols, double[] circMv, double[] totalMv,
            PointInTimeStockExposures.Index membership, List<RawFactor> factors,
            List<Pool> pools, Consumer<Output> sink) {
        if (!outputDates.contains(date)) return new Stats(0, 0, 0);
        var exposures = membership.resolve(date, symbols, circMv, totalMv);
        return compute(date, outputDates, symbols, exposures.logMarketCap(), exposures.industry(),
                factors, pools, sink);
    }

    public static Stats computePlannedWithMembership(LocalDate date, FactorIncrementalPlanner.Plan plan,
            String[] symbols, double[] circMv, double[] totalMv,
            PointInTimeStockExposures.Index membership, List<RawFactor> factors,
            List<Pool> pools, Consumer<Output> sink) {
        if (!plan.outputDates().contains(date)) return new Stats(0, 0, 0);
        var exposures = membership.resolve(date, symbols, circMv, totalMv);
        return computePlanned(date, plan, symbols, exposures.logMarketCap(), exposures.industry(),
                factors, pools, sink);
    }

    /** Only explicitly requested output dates may pass; warmup dates emit nothing.
     * Arrays must not be mutated by the caller or sink until this method returns.
     * The sink owns each result's arrays and may retain them independently.
     */
    public static Stats compute(LocalDate date, Set<LocalDate> outputDates,
            String[] symbols, double[] logMarketCap, String[] industry,
            List<RawFactor> factors, List<Pool> pools, Consumer<Output> sink) {
        return computeSelected(date, outputDates, symbols, logMarketCap, industry, factors, pools, sink,
                (pool, factor) -> true);
    }

    /** Consume the actual section plan so completed pool/factor sections are not recomputed. */
    public static Stats computePlanned(LocalDate date, FactorIncrementalPlanner.Plan plan,
            String[] symbols, double[] logMarketCap, String[] industry,
            List<RawFactor> factors, List<Pool> pools, Consumer<Output> sink) {
        var sections = new HashSet<FactorIncrementalPlanner.Section>();
        for (var pending : plan.sections()) sections.add(pending.request().section());
        var available = new HashSet<FactorIncrementalPlanner.Section>();
        for (Pool pool : pools) for (RawFactor factor : factors)
            available.add(new FactorIncrementalPlanner.Section(date, factor.definition().factorId(),
                    factor.definition().version(), pool.id()));
        for (var section : sections)
            if (section.date().equals(date) && !available.contains(section))
                throw new IllegalArgumentException("Planned section has no input: " + section);
        return computeSelected(date, Set.copyOf(plan.outputDates()), symbols, logMarketCap, industry,
                factors, pools, sink, (pool, factor) -> sections.contains(
                        new FactorIncrementalPlanner.Section(date, factor.factorId(), factor.version(), pool)));
    }

    private static Stats computeSelected(LocalDate date, Set<LocalDate> outputDates,
            String[] symbols, double[] logMarketCap, String[] industry,
            List<RawFactor> factors, List<Pool> pools, Consumer<Output> sink,
            java.util.function.BiPredicate<String, FactorDefinition> selected) {
        Objects.requireNonNull(date);
        Objects.requireNonNull(outputDates);
        Objects.requireNonNull(sink);
        if (!outputDates.contains(date)) return new Stats(0, 0, 0);
        int n = symbols.length;
        if (logMarketCap.length != n || industry.length != n)
            throw new IllegalArgumentException("Misaligned shared exposures");
        var distinct = new HashSet<String>();
        for (String symbol : symbols)
            if (symbol == null || !distinct.add(symbol))
                throw new IllegalArgumentException("Unique shared symbols required");
        var factorIds = new HashSet<String>();
        for (RawFactor factor : factors) {
            Objects.requireNonNull(factor.definition());
            if (factor.values().length != n || !factorIds.add(factor.definition().factorId()))
                throw new IllegalArgumentException("Misaligned or duplicate factor");
        }
        var poolIds = new HashSet<String>();
        for (Pool pool : pools) {
            if (pool.id() == null || pool.id().isBlank() || !poolIds.add(pool.id()))
                throw new IllegalArgumentException("Unique pool IDs required");
            var members = new HashSet<Integer>();
            for (int index : pool.memberIndices())
                if (index < 0 || index >= n || !members.add(index))
                    throw new IllegalArgumentException("Invalid or duplicate pool member index");
        }
        long emitted = 0;
        try (var cache = new FactorNeutralization.ProjectionCache(64)) {
            for (Pool pool : pools) {
                int[] indices = pool.memberIndices();
                String[] selectedSymbols = new String[indices.length];
                String[] selectedIndustry = new String[indices.length];
                double[] selectedSize = new double[indices.length];
                double[] scratch = new double[indices.length];
                for (int j = 0; j < indices.length; j++) {
                    int i = indices[j];
                    selectedSymbols[j] = symbols[i];
                    selectedIndustry[j] = industry[i];
                    selectedSize[j] = logMarketCap[i];
                }
                List<String> outputSymbols = List.of(selectedSymbols);
                for (RawFactor factor : factors) {
                    if (!selected.test(pool.id(), factor.definition())) continue;
                    for (int j = 0; j < indices.length; j++)
                        scratch[j] = factor.values()[indices[j]];
                    var result = FactorPreprocessing.compute(factor.definition(), selectedSymbols,
                            scratch, selectedSize, selectedIndustry, cache);
                    sink.accept(new Output(date, pool.id(), factor.definition(), outputSymbols, result));
                    emitted++;
                }
            }
            return new Stats(emitted, cache.builds(), cache.hits());
        }
    }
}
