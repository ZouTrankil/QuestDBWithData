package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Latest valid earnings announcement within the requested as-of boundary, then covered-weight aggregation. */
public final class EtfEarningsOutlook {
    private EtfEarningsOutlook() {}

    public record Event(String stock, LocalDate announced, double value) {}

    public record Exposure(String etf, String stock, double weight) {}

    public record Result(
            String etf, LocalDate date, double weightedOutlook, double coverage, double value) {}

    public static double forecast(double lowPercent, double highPercent) {
        return clip((lowPercent + highPercent) / 200);
    }

    public static double express(double explicitPercent, double priorProfit, double income) {
        return clip(
                !Double.isNaN(explicitPercent)
                        ? explicitPercent / 100
                        : FactorSeries.ratio(income, priorProfit) - 1);
    }

    public static Map<String, Event> latest(
            List<Event> forecasts, List<Event> express, LocalDate asOf) {
        Objects.requireNonNull(asOf);
        var result = new LinkedHashMap<String, Event>();
        // Python concatenates forecasts first; equal-date valid express rows therefore win.
        for (var source : List.of(forecasts, express))
            for (var event : source) {
                if (event.announced() == null
                        || event.announced().isAfter(asOf)
                        || Double.isNaN(event.value())) continue;
                if (event.stock() == null || event.stock().isBlank())
                    throw new IllegalArgumentException("Earnings stock identity required");
                var prior = result.get(event.stock());
                if (prior == null || !event.announced().isBefore(prior.announced()))
                    result.put(event.stock(), event);
            }
        return Collections.unmodifiableMap(result);
    }

    public static List<Result> aggregate(
            Map<String, Event> latest, List<Exposure> members, LocalDate date) {
        if (latest.isEmpty() || members.isEmpty()) return List.of();
        var totals = new LinkedHashMap<String, double[]>();
        for (var member : members) {
            if (!Double.isFinite(member.weight()) || member.weight() < 0)
                throw new IllegalArgumentException("Invalid component weight");
            var sum = totals.computeIfAbsent(member.etf(), ignored -> new double[2]);
            var event = latest.get(member.stock());
            if (event != null && !Double.isNaN(event.value())) {
                if (event.announced().isAfter(date))
                    throw new IllegalArgumentException(
                            "Future earnings cannot enter a past ETF factor");
                sum[0] += member.weight() * event.value();
                sum[1] += member.weight();
            }
        }
        var result = new ArrayList<Result>();
        totals.forEach(
                (etf, sum) ->
                        result.add(
                                new Result(
                                        etf,
                                        date,
                                        sum[0],
                                        sum[1],
                                        sum[1] > 0 ? sum[0] / sum[1] : Double.NaN)));
        return List.copyOf(result);
    }

    private static double clip(double value) {
        return Math.max(-2, Math.min(2, value));
    }
}
