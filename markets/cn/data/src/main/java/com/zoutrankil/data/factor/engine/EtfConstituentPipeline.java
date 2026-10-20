package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Connects shared stock inputs and dated holdings to ETF temporal/cross-sectional features. */
public final class EtfConstituentPipeline {
    private EtfConstituentPipeline() {}

    public record Result(EtfSharedPanel.Result market, List<EtfComponentPanel.Output> components) {}

    public static Result compute(
            EtfMarketInputs.Prepared etfs,
            Map<EtfSharedPanel.Key, Double> shares,
            List<DailyFactorPanel.Security> stocks,
            Map<LocalDate, List<EtfConstituentWeights.Member>> holdings,
            Map<LocalDate, List<EtfComponentPanel.L2>> l2,
            boolean l2SourcePresent) {
        var validEtfs = new HashSet<String>();
        etfs.inputs().forEach(input -> validEtfs.add(input.symbol()));
        var components = new ArrayList<EtfComponentPanel.Output>();
        var breadth = new HashMap<EtfSharedPanel.Key, Double>();
        for (var date : new TreeSet<>(holdings.keySet())) {
            var members =
                    holdings.get(date).stream()
                            .filter(member -> validEtfs.contains(member.etf()))
                            .toList();
            var rows =
                    EtfComponentPanel.compute(
                            date,
                            members,
                            EtfComponentPanel.stockReturns(date, stocks),
                            l2.getOrDefault(date, List.of()),
                            l2SourcePresent);
            components.addAll(rows);
            for (var row : rows)
                breadth.put(
                        new EtfSharedPanel.Key(row.etf(), date),
                        row.columns().get("component_up_breadth_1d"));
        }
        // Recovery ranks the entire observed ETF market, including ETFs without constituent
        // coverage.
        var market = EtfSharedPanel.compute(etfs.inputs(), etfs.categories(), shares, breadth);
        return new Result(market, List.copyOf(components));
    }
}
