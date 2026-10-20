package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.factor.engine.DailyFactorPanel;
import com.zoutrankil.data.factor.engine.PointInTimeStockPools;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Resolve publication membership from the same frozen panel used for raw calculation. */
public final class StockPublicationScope {
    private StockPublicationScope() {}

    public record Day(LocalDate date, String membershipHash, Set<String> symbols) {
        public Day {
            symbols = Collections.unmodifiableSet(new TreeSet<>(symbols));
        }
    }

    public static Map<LocalDate, Day> resolve(
            List<DailyFactorPanel.Security> history,
            List<DailyFactorPanel.Features> panel,
            Set<LocalDate> outputDates) {
        var sources = new HashMap<String, DailyFactorPanel.Security>();
        for (var source : history)
            if (sources.putIfAbsent(source.symbol(), source) != null)
                throw new IllegalArgumentException("Duplicate publication source symbol");
        var index = new PointInTimeStockPools.Index(List.of());
        var result = new TreeMap<LocalDate, Day>();
        for (var date : new TreeSet<>(outputDates)) {
            var stocks = new ArrayList<PointInTimeStockPools.Stock>();
            for (var feature : panel) {
                int position = Arrays.binarySearch(feature.dates(), date);
                if (position < 0) continue;
                var source =
                        Objects.requireNonNull(
                                sources.get(feature.symbol()), "Missing source history");
                int days = Arrays.binarySearch(source.dates(), date) + 1;
                if (days < 1)
                    throw new IllegalArgumentException("Publication date absent from history");
                double st = value(feature, "is_st", position);
                double suspended = value(feature, "is_suspended", position);
                boolean tradable =
                        feature.columns().containsKey("is_tradable")
                                ? value(feature, "is_tradable", position) == 1
                                : st == 0
                                        && suspended == 0
                                        && value(feature, "amount", position) > 0;
                stocks.add(
                        new PointInTimeStockPools.Stock(
                                feature.symbol(),
                                value(feature, "total_mv", position),
                                days,
                                st,
                                suspended,
                                tradable));
            }
            var resolution = index.resolve(date, stocks, List.of("all_market")).getFirst();
            if (!resolution.available())
                throw new IllegalStateException("Publication membership unavailable: " + date);
            var symbols = new TreeSet<String>();
            resolution.members().stream()
                    .filter(PointInTimeStockPools.Member::included)
                    .forEach(member -> symbols.add(member.symbol()));
            if (symbols.isEmpty())
                throw new IllegalStateException("Empty publication membership: " + date);
            result.put(
                    date,
                    new Day(date, NativePoolFactors.membershipHash(date, resolution), symbols));
        }
        return Collections.unmodifiableMap(result);
    }

    private static double value(DailyFactorPanel.Features feature, String column, int position) {
        var values = feature.columns().get(column);
        if (values == null || values.length != feature.dates().length)
            throw new IllegalArgumentException(
                    "Missing or misaligned publication membership column: " + column);
        return values[position];
    }
}
