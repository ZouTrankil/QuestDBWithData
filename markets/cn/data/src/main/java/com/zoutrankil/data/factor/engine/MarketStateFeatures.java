package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Whole-market aggregates over the shared raw panel; rolling history is consumed before output slicing. */
public final class MarketStateFeatures {
    private MarketStateFeatures() {}

    public record Result(LocalDate[] dates, Map<String, double[]> columns) {}

    private static final class Aggregate {
        int rows, up, down, high, low, limitUp, limitDown, returns, turnovers;
        double mean, m2, amount;
        double[] turnover = new double[64];

        void addReturn(double value) {
            if (!Double.isFinite(value)) return;
            returns++;
            double delta = value - mean;
            mean += delta / returns;
            m2 += delta * (value - mean);
        }

        void addTurnover(double value) {
            if (!Double.isFinite(value)) return;
            if (turnovers == turnover.length) turnover = Arrays.copyOf(turnover, turnovers * 2);
            turnover[turnovers++] = value;
        }

        double median() {
            if (turnovers == 0) return Double.NaN;
            Arrays.sort(turnover, 0, turnovers);
            int mid = turnovers / 2;
            return turnovers % 2 == 0 ? (turnover[mid - 1] + turnover[mid]) / 2 : turnover[mid];
        }
    }

    public static Result compute(
            List<DailyFactorPanel.Security> securities, LocalDate from, LocalDate through) {
        if (from == null || through == null || through.isBefore(from))
            throw new IllegalArgumentException("Ordered output dates required");
        var symbols = new HashSet<String>();
        var dates = new TreeMap<LocalDate, Aggregate>();
        for (var security : securities) {
            if (security.symbol() == null || !symbols.add(security.symbol()))
                throw new IllegalArgumentException("Unique market securities required");
            int n = security.dates().length;
            for (String name :
                    List.of("close", "amount", "turnover_rate", "up_limit", "down_limit")) {
                var column = security.columns().get(name);
                if (column == null || column.length != n)
                    throw new IllegalArgumentException(
                            "Missing or misaligned market column: " + name);
                for (double value : column)
                    if (Double.isInfinite(value))
                        throw new IllegalArgumentException("Infinite market input");
            }
            var price = security.columns().get("close");
            double previous = Double.NaN;
            for (int i = 0; i < n; i++) {
                LocalDate date = security.dates()[i];
                if (date == null || i > 0 && !date.isAfter(security.dates()[i - 1]))
                    throw new IllegalArgumentException("Strictly ordered security dates required");
                var aggregate = dates.computeIfAbsent(date, ignored -> new Aggregate());
                aggregate.rows++;
                double filled = Double.isNaN(price[i]) ? previous : price[i],
                        ret = filled / previous - 1;
                previous = filled;
                if (ret > 0) aggregate.up++;
                if (ret < 0) aggregate.down++;
                aggregate.addReturn(ret);
                int count = 0;
                double high = Double.NEGATIVE_INFINITY, low = Double.POSITIVE_INFINITY;
                for (int j = Math.max(0, i - 19); j <= i; j++)
                    if (Double.isFinite(price[j])) {
                        count++;
                        high = Math.max(high, price[j]);
                        low = Math.min(low, price[j]);
                    }
                if (count >= 10 && price[i] >= high) aggregate.high++;
                if (count >= 10 && price[i] <= low) aggregate.low++;
                if (price[i] >= security.columns().get("up_limit")[i] * (1 - 1e-6))
                    aggregate.limitUp++;
                if (price[i] <= security.columns().get("down_limit")[i] * (1 + 1e-6))
                    aggregate.limitDown++;
                double amount = security.columns().get("amount")[i];
                if (Double.isFinite(amount)) aggregate.amount += amount;
                aggregate.addTurnover(security.columns().get("turnover_rate")[i]);
            }
        }
        var allDates = dates.keySet().toArray(LocalDate[]::new);
        var columns = new LinkedHashMap<String, double[]>();
        for (String name :
                List.of(
                        "market_up_ratio",
                        "market_down_ratio",
                        "market_new_high_20_ratio",
                        "market_new_low_20_ratio",
                        "market_limit_up_count",
                        "market_limit_down_count",
                        "market_amount",
                        "market_turnover_rate",
                        "market_dispersion",
                        "market_return")) columns.put(name, new double[allDates.length]);
        for (int i = 0; i < allDates.length; i++) {
            var a = dates.get(allDates[i]);
            columns.get("market_up_ratio")[i] = (double) a.up / a.rows;
            columns.get("market_down_ratio")[i] = (double) a.down / a.rows;
            columns.get("market_new_high_20_ratio")[i] = (double) a.high / a.rows;
            columns.get("market_new_low_20_ratio")[i] = (double) a.low / a.rows;
            columns.get("market_limit_up_count")[i] = a.limitUp;
            columns.get("market_limit_down_count")[i] = a.limitDown;
            columns.get("market_amount")[i] = a.amount;
            columns.get("market_turnover_rate")[i] = a.median();
            columns.get("market_return")[i] = a.returns == 0 ? Double.NaN : a.mean;
            columns.get("market_dispersion")[i] =
                    a.returns < 2 ? Double.NaN : Math.sqrt(Math.max(0, a.m2 / (a.returns - 1)));
        }
        columns.put(
                "market_volatility_20d",
                FactorSeries.rolling(columns.get("market_return"), 20, 10, true));
        int begin = 0, end = allDates.length;
        while (begin < end && allDates[begin].isBefore(from)) begin++;
        while (end > begin && allDates[end - 1].isAfter(through)) end--;
        var selected = new LinkedHashMap<String, double[]>();
        for (var entry : columns.entrySet())
            selected.put(entry.getKey(), Arrays.copyOfRange(entry.getValue(), begin, end));
        return new Result(
                Arrays.copyOfRange(allDates, begin, end), Collections.unmodifiableMap(selected));
    }
}
