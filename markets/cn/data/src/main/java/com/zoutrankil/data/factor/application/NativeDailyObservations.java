package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import com.zoutrankil.data.factor.engine.DailyFactorFeatures;
import com.zoutrankil.data.factor.engine.DailyFactorPanel.Features;
import com.zoutrankil.data.factor.engine.FactorFormulaEngine;
import com.zoutrankil.data.factor.engine.StructuralFinancialFeatures;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Native raw observations for explicitly selected daily factors; no pool or direction expansion. */
public final class NativeDailyObservations {
    private NativeDailyObservations() {}

    /** Generate only the GLOBAL sections selected by the incremental planner, without a date/factor cross product. */
    public static long streamPlanned(
            List<Features> panel,
            FactorIncrementalPlanner.Plan plan,
            FactorDefinitionCatalog catalog,
            LocalDateTime computedAt,
            Consumer<List<GlobalFactorObservation>> sink) {
        Objects.requireNonNull(plan);
        var datesByFactor = new LinkedHashMap<String, Set<LocalDate>>();
        var seen = new HashSet<FactorIncrementalPlanner.Section>();
        for (var pending : plan.sections()) {
            var section = pending.request().section();
            var definition = catalog.require(section.factorId());
            if (!section.poolId().equals("GLOBAL")
                    || !section.version().equals(definition.version())
                    || !plan.outputDates().contains(section.date())
                    || !seen.add(section))
                throw new IllegalArgumentException(
                        "Invalid GLOBAL incremental section: " + section);
            datesByFactor
                    .computeIfAbsent(section.factorId(), ignored -> new HashSet<>())
                    .add(section.date());
        }
        // Validate all requested factors and dates before the caller can persist any page.
        for (var entry : datesByFactor.entrySet())
            validatePanel(
                    panel, entry.getValue(), List.of(entry.getKey()), catalog, computedAt, sink);
        long emitted = 0;
        for (var entry : datesByFactor.entrySet())
            emitted +=
                    streamPanel(
                            panel,
                            entry.getValue(),
                            List.of(entry.getKey()),
                            catalog,
                            computedAt,
                            sink);
        return emitted;
    }

    /** Emit from one prepared whole-market panel, reused by raw publication and pool consumers.
     * Source columns and dates stay frozen for the call. The sink receives independently owned pages.
     * The source owner certifies the market coverage; this method makes no completeness claim.
     */
    public static long streamPanel(
            List<Features> panel,
            Set<LocalDate> outputDates,
            List<String> factorIds,
            FactorDefinitionCatalog catalog,
            LocalDateTime computedAt,
            Consumer<List<GlobalFactorObservation>> sink) {
        validatePanel(panel, outputDates, factorIds, catalog, computedAt, sink);
        var definitions = factorIds.stream().map(catalog::require).toList();
        long emitted = 0;
        var page = new ArrayList<GlobalFactorObservation>(250);
        var engine = new FactorFormulaEngine();
        for (var security : panel)
            for (var definition : definitions) {
                double[] raw = engine.compute(definition, security.columns());
                for (int i = 0; i < security.dates().length; i++)
                    if (outputDates.contains(security.dates()[i])) {
                        if (Thread.currentThread().isInterrupted())
                            throw new CancellationException("Panel emission cancelled");
                        if (definition.sourceGroup().equals("global_structural_pit")
                                && security.columns()
                                                .get(StructuralFinancialFeatures.ELIGIBLE_COLUMN)[i]
                                        == 0) continue;
                        page.add(
                                GlobalFactorObservations.raw(
                                        security.dates()[i],
                                        security.symbol(),
                                        definition,
                                        raw[i],
                                        null,
                                        computedAt,
                                        "ok",
                                        "",
                                        null));
                        emitted++;
                        if (page.size() == 250) {
                            sink.accept(List.copyOf(page));
                            page.clear();
                        }
                    }
            }
        if (!page.isEmpty()) sink.accept(List.copyOf(page));
        return emitted;
    }

    private static void validatePanel(
            List<Features> panel,
            Set<LocalDate> outputDates,
            List<String> factorIds,
            FactorDefinitionCatalog catalog,
            LocalDateTime computedAt,
            Consumer<List<GlobalFactorObservation>> sink) {
        Objects.requireNonNull(computedAt);
        Objects.requireNonNull(sink);
        if (outputDates.isEmpty()
                || factorIds.isEmpty()
                || new HashSet<>(factorIds).size() != factorIds.size())
            throw new IllegalArgumentException("Output dates and unique factor selection required");
        var definitions = factorIds.stream().map(catalog::require).toList();
        for (var definition : definitions)
            if (!definition.assetType().equals("stock")
                    || !definition.enabled()
                    || !definition.publishObservations()
                    || !definition.transform().equals("precomputed")
                    || !Set.of(
                                    "canonical_daily",
                                    "daily_price_tech",
                                    "global_structural_daily",
                                    "fundamental_pit",
                                    "global_structural_pit",
                                    "event_pit")
                            .contains(definition.sourceGroup()))
                throw new IllegalArgumentException(
                        "Unsupported panel factor: " + definition.factorId());
        var symbols = new HashSet<String>();
        var availableDates = new HashSet<LocalDate>();
        // Validate the entire declared selection before emitting any page.
        for (var security : panel) {
            if (security.symbol() == null
                    || !security.symbol().matches("[0-9]{6}\\.(SH|SZ|BJ)")
                    || !symbols.add(security.symbol()))
                throw new IllegalArgumentException("Unique mainland securities required");
            LocalDate prior = null;
            for (LocalDate date : security.dates()) {
                if (date == null || prior != null && !date.isAfter(prior))
                    throw new IllegalArgumentException(
                            "Strictly increasing security dates required");
                prior = date;
                availableDates.add(date);
            }
            for (var definition : definitions) {
                if (definition.sourceGroup().equals("global_structural_pit")) {
                    var eligible =
                            security.columns().get(StructuralFinancialFeatures.ELIGIBLE_COLUMN);
                    if (eligible == null || eligible.length != security.dates().length)
                        throw new IllegalArgumentException(
                                "Structural PIT eligibility is missing or misaligned");
                    for (double value : eligible)
                        if (value != 0 && value != 1)
                            throw new IllegalArgumentException(
                                    "Structural PIT eligibility must be zero or one");
                }
                double[] column =
                        security.columns().get(FactorFormulaEngine.inputColumn(definition));
                if (column == null || column.length != security.dates().length)
                    throw new IllegalArgumentException(
                            "Missing or misaligned panel input: " + definition.sourceColumn());
            }
        }
        if (!availableDates.containsAll(outputDates))
            throw new IllegalArgumentException("Requested output dates missing from panel");
    }

    public static List<GlobalFactorObservation> compute(
            String symbol,
            LocalDate[] dates,
            Map<String, double[]> input,
            Set<LocalDate> outputDates,
            List<String> factorIds,
            FactorDefinitionCatalog catalog,
            LocalDateTime computedAt) {
        if (symbol == null || !symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)"))
            throw new IllegalArgumentException("Mainland security required");
        if (factorIds.isEmpty() || new HashSet<>(factorIds).size() != factorIds.size())
            throw new IllegalArgumentException("Explicit unique factor selection required");
        if (outputDates.isEmpty() || !new HashSet<>(List.of(dates)).containsAll(outputDates))
            throw new IllegalArgumentException("Every requested output date needs a source row");
        if ((long) outputDates.size() * factorIds.size() > 100_000)
            throw new IllegalArgumentException("Observation result exceeds row budget");
        var definitions = factorIds.stream().map(catalog::require).toList();
        for (var definition : definitions)
            if (!definition.assetType().equals("stock")
                    || !definition.enabled()
                    || !definition.publishObservations()
                    || !Set.of(
                                    "canonical_daily",
                                    "daily_price_tech",
                                    "global_structural_daily",
                                    "fundamental_pit",
                                    "global_structural_pit")
                            .contains(definition.sourceGroup()))
                throw new IllegalArgumentException(
                        "Factor is not an enabled daily stock producer: " + definition.factorId());
        // Input and pool-independent features are computed once for the whole factor selection.
        var columns = new HashMap<>(input);
        columns.putAll(DailyFactorFeatures.compute(dates, input));
        var engine = new FactorFormulaEngine();
        var result = new ArrayList<GlobalFactorObservation>();
        for (var definition : definitions) {
            double[] raw = engine.compute(definition, columns);
            for (int i = 0; i < dates.length; i++)
                if (outputDates.contains(dates[i]))
                    result.add(
                            GlobalFactorObservations.raw(
                                    dates[i],
                                    symbol,
                                    definition,
                                    raw[i],
                                    null,
                                    computedAt,
                                    "ok",
                                    "",
                                    null));
        }
        return List.copyOf(result);
    }
}
