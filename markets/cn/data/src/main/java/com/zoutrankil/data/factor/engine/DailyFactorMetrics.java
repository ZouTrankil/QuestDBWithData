package com.zoutrankil.data.factor.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Single frozen pool/factor sample, matching Python factor_daily_evaluation. */
public final class DailyFactorMetrics {
    private DailyFactorMetrics() {}

    public record Row(String symbol, double factor, double forwardReturn) {}

    public record Result(
            int universeCount,
            int sampleCount,
            double coverage,
            String quality,
            double rankIc,
            double topMinusBottom,
            double decileMonotonicity,
            Boolean win,
            List<String> sampleSymbols,
            List<Double> decileMeanReturns) {}

    public static Result evaluate(List<Row> members) {
        var seen = new HashSet<String>();
        var sample = new ArrayList<Row>();
        for (var row : members) {
            if (row.symbol() == null || !seen.add(row.symbol()))
                throw new IllegalArgumentException("Unique frozen pool members required");
            if (Double.isFinite(row.factor()) && Double.isFinite(row.forwardReturn()))
                sample.add(row);
        }
        sample.sort(Comparator.comparing(Row::symbol));
        int n = sample.size();
        double coverage = members.isEmpty() ? 0 : (double) n / members.size();
        double[] factors = sample.stream().mapToDouble(Row::factor).toArray();
        double[] labels = sample.stream().mapToDouble(Row::forwardReturn).toArray();
        String quality =
                n < 3
                        ? "insufficient_sample"
                        : constant(factors)
                                ? "constant_factor"
                                : constant(labels) ? "constant_label" : "ready";
        double ic = Double.NaN, spread = Double.NaN, monotonicity = Double.NaN;
        var decileReturns = new Double[10];
        Arrays.fill(decileReturns, Double.NaN);
        if (quality.equals("ready")) {
            ic =
                    correlation(
                            FactorCrossSection.normalize(factors, "rank").values(),
                            FactorCrossSection.normalize(labels, "rank").values());
            var ordered = new ArrayList<>(sample);
            // pandas treats -0.0 and +0.0 as one value before the entity tie-break.
            ordered.sort(
                    Comparator.comparingDouble((Row row) -> row.factor() == 0 ? 0 : row.factor())
                            .thenComparing(Row::symbol));
            int buckets = Math.min(10, n), position = 0;
            var means = new double[buckets];
            var indices = new double[buckets];
            for (int bucket = 0; bucket < buckets; bucket++) {
                int size = n / 10 + (bucket < n % 10 ? 1 : 0);
                double mean = 0;
                for (int i = 0; i < size; i++)
                    mean += ordered.get(position++).forwardReturn() / size;
                means[bucket] = mean;
                indices[bucket] = bucket + 1;
                decileReturns[bucket] = finite(mean);
            }
            spread = finite(means[buckets - 1] - means[0]);
            monotonicity = correlation(indices, means);
        }
        return new Result(
                members.size(),
                n,
                coverage,
                quality,
                ic,
                spread,
                monotonicity,
                Double.isFinite(ic) ? ic > 0 : null,
                sample.stream().map(Row::symbol).toList(),
                List.of(decileReturns));
    }

    private static boolean constant(double[] values) {
        for (double value : values) if (value != values[0]) return false;
        return true;
    }

    private static double correlation(double[] a, double[] b) {
        double ma = Arrays.stream(a).average().orElse(Double.NaN);
        double mb = Arrays.stream(b).average().orElse(Double.NaN);
        double aa = 0, bb = 0, ab = 0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i] - ma, y = b[i] - mb;
            aa += x * x;
            bb += y * y;
            ab += x * y;
        }
        return finite(ab / Math.sqrt(aa) / Math.sqrt(bb));
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : Double.NaN;
    }
}
