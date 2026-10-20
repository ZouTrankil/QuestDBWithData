package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.engine.DailyFactorPanel;
import com.zoutrankil.data.factor.engine.FactorFormulaEngine;
import com.zoutrankil.data.factor.engine.L2FactorPanel;
import com.zoutrankil.data.factor.engine.MultiPoolFactorPipeline;
import com.zoutrankil.data.factor.engine.PointInTimeStockPools;
import com.zoutrankil.data.factor.storage.QuestDbStockMembership;
import com.zoutrankil.data.repository.FileEvidenceStore;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** One shared cross-section per date; materialize only the bounded requested pool outputs. */
public final class NativePoolFactors {
    private NativePoolFactors() {}

    public record Result(
            List<Map<String, Object>> coverage,
            List<MultiPoolFactorPipeline.Output> sections,
            long projectionBuilds,
            long projectionHits) {}

    public static Result compute(
            List<DailyFactorPanel.Security> history,
            List<DailyFactorPanel.Features> features,
            QuestDbStockMembership.Snapshot membership,
            List<String> poolIds,
            List<String> factorIds,
            FactorDefinitionCatalog catalog) {
        return computePrepared(
                history, features, membership, poolIds, factorIds, catalog, null, 100_000);
    }

    public static Result compute(
            List<DailyFactorPanel.Security> history,
            List<DailyFactorPanel.Features> features,
            QuestDbStockMembership.Snapshot membership,
            List<String> poolIds,
            List<String> factorIds,
            FactorDefinitionCatalog catalog,
            int maxOutputRows) {
        if (maxOutputRows < 1 || maxOutputRows > FactorPoolEvidence.MAX_ROWS)
            throw new IllegalArgumentException("Bounded pool output required");
        return computePrepared(
                history, features, membership, poolIds, factorIds, catalog, null, maxOutputRows);
    }

    /** Time-series transforms have already run once per symbol; pools only gather the resulting values. */
    public static Result computeL2(
            List<DailyFactorPanel.Security> history,
            List<DailyFactorPanel.Features> features,
            QuestDbStockMembership.Snapshot membership,
            List<String> poolIds,
            List<String> factorIds,
            FactorDefinitionCatalog catalog,
            List<L2FactorPanel.Section> l2) {
        return computeL2(history, features, membership, poolIds, factorIds, catalog, l2, 100_000);
    }

    public static Result computeL2(
            List<DailyFactorPanel.Security> history,
            List<DailyFactorPanel.Features> features,
            QuestDbStockMembership.Snapshot membership,
            List<String> poolIds,
            List<String> factorIds,
            FactorDefinitionCatalog catalog,
            List<L2FactorPanel.Section> l2,
            int maxOutputRows) {
        if (maxOutputRows < 1 || maxOutputRows > 2_000_000)
            throw new IllegalArgumentException("Bounded L2 pool output required");
        var prepared = new HashMap<String, L2FactorPanel.Section>();
        for (var section : l2)
            if (prepared.putIfAbsent(section.symbol(), section) != null)
                throw new IllegalArgumentException("Duplicate prepared L2 security");
        for (String id : factorIds)
            if (!catalog.require(id).sourceGroup().equals("l2_microstructure"))
                throw new IllegalArgumentException("L2 factor selection required");
        return computePrepared(
                history,
                features,
                membership,
                poolIds,
                factorIds,
                catalog,
                prepared,
                maxOutputRows);
    }

    private static Result computePrepared(
            List<DailyFactorPanel.Security> history,
            List<DailyFactorPanel.Features> features,
            QuestDbStockMembership.Snapshot membership,
            List<String> poolIds,
            List<String> factorIds,
            FactorDefinitionCatalog catalog,
            Map<String, L2FactorPanel.Section> prepared,
            int maxOutputRows) {
        var sources = new HashMap<String, DailyFactorPanel.Security>();
        history.forEach(s -> sources.put(s.symbol(), s));
        var dates = new TreeSet<LocalDate>();
        features.forEach(s -> dates.addAll(Arrays.asList(s.dates())));
        var coverage = new ArrayList<Map<String, Object>>();
        var output = new ArrayList<MultiPoolFactorPipeline.Output>();
        long builds = 0, hits = 0;
        long[] rows = {0};
        for (var date : dates) {
            var selected =
                    features.stream()
                            .filter(s -> Arrays.binarySearch(s.dates(), date) >= 0)
                            .toList();
            int n = selected.size();
            String[] symbols = new String[n];
            double[] circ = new double[n], total = new double[n];
            var stocks = new ArrayList<PointInTimeStockPools.Stock>();
            var columns = new HashMap<String, double[]>();
            for (String id : factorIds) {
                var definition = catalog.require(id);
                if (!definition.assetType().equals("stock"))
                    throw new IllegalArgumentException("Pool factors must be stocks");
                if (prepared == null && definition.sourceGroup().equals("l2_microstructure"))
                    throw new IllegalArgumentException(
                            "L2 time-series transforms must precede pool processing");
                columns.computeIfAbsent(
                        prepared == null ? FactorFormulaEngine.inputColumn(definition) : id,
                        ignored -> new double[n]);
            }
            var observed = new BitSet(n);
            for (int i = 0; i < n; i++) {
                var feature = selected.get(i);
                int j = Arrays.binarySearch(feature.dates(), date);
                symbols[i] = feature.symbol();
                circ[i] = value(feature, "circ_mv", j);
                total[i] = value(feature, "total_mv", j);
                var source = Objects.requireNonNull(sources.get(feature.symbol()));
                int tradeDays = Arrays.binarySearch(source.dates(), date) + 1;
                if (tradeDays < 1)
                    throw new IllegalStateException("Output date missing from source history");
                double st = value(feature, "is_st", j),
                        suspended = value(feature, "is_suspended", j);
                // Matches ResearchUniverse._normalize_market when is_tradable is absent.
                boolean tradable =
                        feature.columns().containsKey("is_tradable")
                                ? value(feature, "is_tradable", j) == 1
                                : st == 0 && suspended == 0 && value(feature, "amount", j) > 0;
                stocks.add(
                        new PointInTimeStockPools.Stock(
                                symbols[i], total[i], tradeDays, st, suspended, tradable));
                if (prepared != null) {
                    var l2 = prepared.get(symbols[i]);
                    int position = l2 == null ? -1 : Arrays.binarySearch(l2.dates(), date);
                    if (position >= 0) observed.set(i);
                    for (var entry : columns.entrySet()) {
                        var values = l2 == null ? null : l2.factors().get(entry.getKey());
                        if (position >= 0 && (values == null || values.length != l2.dates().length))
                            throw new IllegalArgumentException(
                                    "Missing prepared L2 factor: " + entry.getKey());
                        entry.getValue()[i] = position < 0 ? Double.NaN : values[position];
                    }
                } else
                    for (var entry : columns.entrySet()) {
                        if (!feature.columns().containsKey(entry.getKey()))
                            throw new IllegalArgumentException(
                                    "Missing pool factor input: " + entry.getKey());
                        entry.getValue()[i] = value(feature, entry.getKey(), j);
                    }
            }
            var resolutions = membership.pools().resolve(date, stocks, poolIds);
            var pools = new ArrayList<MultiPoolFactorPipeline.Pool>();
            for (var resolution : resolutions) {
                var row = new LinkedHashMap<String, Object>();
                row.put("date", date);
                row.put("pool", resolution.poolId());
                row.put("version", PointInTimeStockPools.VERSION);
                row.put("available", resolution.available());
                row.put("reason", resolution.unavailableReason());
                row.put("membershipAsOf", resolution.membershipAsOf());
                row.put("membershipHash", membershipHash(date, resolution));
                row.put("members", resolution.members());
                coverage.add(row);
                if (resolution.available()) {
                    var pool = resolution.asPool();
                    // Freeze membership on the whole market, then inner-join observed L2 keys like
                    // Python.
                    if (prepared != null)
                        pool =
                                new MultiPoolFactorPipeline.Pool(
                                        pool.id(),
                                        Arrays.stream(pool.memberIndices())
                                                .filter(observed::get)
                                                .toArray());
                    if (prepared == null || pool.memberIndices().length > 0) pools.add(pool);
                }
            }
            var engine = new FactorFormulaEngine();
            var factors = new ArrayList<MultiPoolFactorPipeline.RawFactor>();
            for (String id : factorIds) {
                var definition = catalog.require(id);
                factors.add(
                        new MultiPoolFactorPipeline.RawFactor(
                                definition,
                                prepared == null
                                        ? engine.compute(definition, columns)
                                        : columns.get(id)));
            }
            var stats =
                    MultiPoolFactorPipeline.computeWithMembership(
                            date,
                            dates,
                            symbols,
                            circ,
                            total,
                            membership.exposures(),
                            factors,
                            pools,
                            section -> {
                                rows[0] += section.symbols().size();
                                if (rows[0] > maxOutputRows)
                                    throw new IllegalArgumentException(
                                            "Pool output exceeds row budget; split dates or factors");
                                output.add(section);
                            });
            builds += stats.projectionBuilds();
            hits += stats.projectionHits();
        }
        return new Result(List.copyOf(coverage), List.copyOf(output), builds, hits);
    }

    private static double value(DailyFactorPanel.Features feature, String column, int index) {
        var values = feature.columns().get(column);
        return values == null ? Double.NaN : values[index];
    }

    /** Canonical Python membership identity, independent of source row order. */
    public static String membershipHash(
            LocalDate date, PointInTimeStockPools.Resolution resolution) {
        if (!resolution.available()) return "";
        var payload = new TreeMap<String, Object>();
        payload.put("universe_id", resolution.poolId());
        payload.put("universe_version", PointInTimeStockPools.VERSION);
        payload.put("signal_date", date.toString());
        payload.put("membership_as_of", resolution.membershipAsOf().toString());
        payload.put(
                "members",
                resolution.members().stream()
                        .filter(PointInTimeStockPools.Member::included)
                        .map(PointInTimeStockPools.Member::symbol)
                        .sorted()
                        .toList());
        try {
            return FileEvidenceStore.sha256(JobDefinitionJson.mapper().writeValueAsBytes(payload));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot encode pool identity", error);
        }
    }
}
