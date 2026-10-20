package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** One date of shared stock/L2 observations projected through all ETF constituent baskets. */
public final class EtfComponentPanel {
    private EtfComponentPanel() {}

    public record Stock(String symbol, double return1, double return5) {}

    public record L2(
            String symbol,
            double inflow,
            double mfi,
            double obi1,
            double obi5,
            double ofi,
            double spread,
            double depth) {}

    public record Output(String etf, LocalDate date, Map<String, Double> columns) {}

    /** Borrow existing stock close arrays. Never rerun the full daily factor kernels for ETF returns. */
    public static List<Stock> stockReturns(
            LocalDate date, List<DailyFactorPanel.Security> securities) {
        Objects.requireNonNull(date);
        var result = new ArrayList<Stock>();
        var symbols = new HashSet<String>();
        for (var security : securities) {
            if (security.symbol() == null || !symbols.add(security.symbol()))
                throw new IllegalArgumentException("Unique stock symbols required");
            var dates = security.dates();
            var close = security.columns().get("close");
            if (close == null || close.length != dates.length)
                throw new IllegalArgumentException("Aligned close array required");
            for (int i = 0; i < dates.length; i++)
                if (dates[i] == null || i > 0 && !dates[i].isAfter(dates[i - 1]))
                    throw new IllegalArgumentException("Increasing unique stock dates required");
            int row = Arrays.binarySearch(dates, date);
            if (row < 0) continue;
            // Python pct_change(fill_method=None) uses observed rows, including NaN rows.
            double ret1 = row < 1 ? Double.NaN : close[row] / close[row - 1] - 1;
            double ret5 = row < 5 ? Double.NaN : close[row] / close[row - 5] - 1;
            result.add(new Stock(security.symbol(), ret1, ret5));
        }
        return List.copyOf(result);
    }

    public static List<Output> compute(
            LocalDate date,
            List<EtfConstituentWeights.Member> members,
            List<Stock> stocks,
            List<L2> l2,
            boolean l2SourcePresent) {
        Objects.requireNonNull(date);
        var exposures = new HashMap<String, List<EtfConstituentWeights.Member>>();
        var weights = new TreeMap<String, List<Double>>();
        var keys = new HashSet<List<String>>();
        for (var member : members) {
            if (!keys.add(List.of(member.etf(), member.stock())))
                throw new IllegalArgumentException("Duplicate normalized constituent");
            if (member.announced() == null || member.announced().isAfter(date))
                throw new IllegalArgumentException(
                        "ETF constituent is not available on output date");
            exposures.computeIfAbsent(member.stock(), ignored -> new ArrayList<>()).add(member);
            weights.computeIfAbsent(member.etf(), ignored -> new ArrayList<>())
                    .add(member.weight());
        }
        var joinedStocks = new HashMap<String, List<EtfComponentFeatures.Stock>>();
        for (var stock : stocks)
            for (var member : exposures.getOrDefault(stock.symbol(), List.of()))
                joinedStocks
                        .computeIfAbsent(member.etf(), ignored -> new ArrayList<>())
                        .add(
                                new EtfComponentFeatures.Stock(
                                        stock.symbol(),
                                        member.weight(),
                                        stock.return1(),
                                        stock.return5()));
        var joinedL2 = new HashMap<String, List<EtfComponentFeatures.L2>>();
        if (l2SourcePresent)
            for (var row : l2)
                for (var member : exposures.getOrDefault(row.symbol(), List.of()))
                    joinedL2.computeIfAbsent(member.etf(), ignored -> new ArrayList<>())
                            .add(
                                    new EtfComponentFeatures.L2(
                                            member.weight(),
                                            row.inflow(),
                                            row.mfi(),
                                            row.obi1(),
                                            row.obi5(),
                                            row.ofi(),
                                            row.spread(),
                                            row.depth()));
        var result = new ArrayList<Output>();
        for (var entry : weights.entrySet()) {
            var observations = joinedStocks.getOrDefault(entry.getKey(), List.of());
            if (observations.isEmpty()) continue;
            var features =
                    EtfComponentFeatures.compute(
                            observations,
                            joinedL2.getOrDefault(entry.getKey(), List.of()),
                            entry.getValue().stream().mapToDouble(Double::doubleValue).toArray(),
                            l2SourcePresent);
            result.add(new Output(entry.getKey(), date, features));
        }
        return List.copyOf(result);
    }
}
