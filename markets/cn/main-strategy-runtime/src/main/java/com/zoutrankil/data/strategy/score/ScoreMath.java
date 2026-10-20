package com.zoutrankil.data.strategy.score;

import com.zoutrankil.data.factor.engine.FactorCrossSection;
import com.zoutrankil.data.factor.engine.FactorSeries;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

final class ScoreMath {
    private ScoreMath() {}

    static double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value == null || value.toString().isBlank()) return Double.NaN;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    static boolean flag(Object value) {
        return value != null
                && Set.of("true", "1", "1.0", "yes")
                        .contains(value.toString().toLowerCase(Locale.ROOT));
    }

    static double fill(double value, double fallback) {
        return Double.isNaN(value) ? fallback : value;
    }

    static double clip(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    static double ratio(double left, double right) {
        return Math.abs(right) > 1e-9 ? left / right : Double.NaN;
    }

    static double[] ranks(double[] values, boolean ascending) {
        if (Arrays.stream(values).anyMatch(Double::isInfinite))
            throw new IllegalArgumentException("Infinite rank input");
        double[] transformed = ascending ? values : FactorSeries.map(values, v -> -v);
        return FactorCrossSection.normalize(transformed, "rank").values();
    }

    static Map<LocalDate, List<MutableRow>> byDate(List<MutableRow> rows) {
        var result = new TreeMap<LocalDate, List<MutableRow>>();
        for (var row : rows)
            result.computeIfAbsent(row.date, ignored -> new ArrayList<>()).add(row);
        return result;
    }

    static Map<String, List<MutableRow>> bySymbol(List<MutableRow> rows) {
        var result = new TreeMap<String, List<MutableRow>>();
        for (var row : rows)
            result.computeIfAbsent(row.symbol, ignored -> new ArrayList<>()).add(row);
        result.values().forEach(group -> group.sort(Comparator.comparing(row -> row.date)));
        return result;
    }

    static void rank(
            List<MutableRow> rows,
            String source,
            String target,
            boolean ascending,
            double fallback) {
        for (var group : byDate(rows).values()) {
            var values = group.stream().mapToDouble(row -> row.n(source)).toArray();
            var ranked = ranks(values, ascending);
            for (int i = 0; i < ranked.length; i++)
                group.get(i).put(target, fill(ranked[i], fallback));
        }
    }

    static void meanRank(List<MutableRow> rows, String target, String... columns) {
        var present =
                Arrays.stream(columns)
                        .filter(c -> rows.stream().anyMatch(row -> row.values.containsKey(c)))
                        .toList();
        for (var group : byDate(rows).values()) {
            double[] sum = new double[group.size()];
            int[] count = new int[group.size()];
            for (String column : present) {
                var rank = ranks(group.stream().mapToDouble(row -> row.n(column)).toArray(), true);
                for (int i = 0; i < rank.length; i++)
                    if (!Double.isNaN(rank[i])) {
                        sum[i] += rank[i];
                        count[i]++;
                    }
            }
            for (int i = 0; i < group.size(); i++)
                group.get(i).put(target, count[i] == 0 ? .5 : sum[i] / count[i]);
        }
    }

    static void rolling(
            List<MutableRow> rows,
            String source,
            String target,
            int window,
            int minimum,
            boolean std) {
        for (var group : bySymbol(rows).values()) {
            var result =
                    FactorSeries.rolling(
                            group.stream().mapToDouble(row -> row.n(source)).toArray(),
                            window,
                            minimum,
                            std);
            for (int i = 0; i < result.length; i++) group.get(i).put(target, result[i]);
        }
    }

    static void change(
            List<MutableRow> rows, String source, String target, int periods, boolean forwardFill) {
        for (var group : bySymbol(rows).values()) {
            double[] values = group.stream().mapToDouble(row -> row.n(source)).toArray();
            if (forwardFill) values = FactorSeries.forwardFill(values);
            for (int i = 0; i < values.length; i++)
                group.get(i)
                        .put(
                                target,
                                i < periods ? Double.NaN : values[i] / values[i - periods] - 1);
        }
    }

    static void extreme(
            List<MutableRow> rows,
            String source,
            String target,
            int window,
            int minimum,
            boolean maximum) {
        for (var group : bySymbol(rows).values())
            for (int i = 0; i < group.size(); i++) {
                int count = 0;
                double result = maximum ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
                for (int j = Math.max(0, i - window + 1); j <= i; j++) {
                    double v = group.get(j).n(source);
                    if (!Double.isNaN(v)) {
                        count++;
                        result = maximum ? Math.max(result, v) : Math.min(result, v);
                    }
                }
                group.get(i).put(target, count < minimum ? Double.NaN : result);
            }
    }

    static double mean(List<MutableRow> rows, ToDoubleFunction<MutableRow> getter) {
        return rows.stream()
                .mapToDouble(getter)
                .filter(v -> !Double.isNaN(v))
                .average()
                .orElse(Double.NaN);
    }

    /** 对齐 pandas 分组求和及均值：除法前采用补偿累加，这也影响并列排名的判定。 */
    static final class GroupSum {
        private double value, correction;
        private int count;

        void add(double observation) {
            if (Double.isNaN(observation)) return;
            double adjusted = observation - correction, next = value + adjusted;
            correction = next - value - adjusted;
            value = next;
            count++;
        }

        double sum() {
            return value;
        }

        double mean() {
            return count == 0 ? Double.NaN : value / count;
        }

        int count() {
            return count;
        }
    }

    static List<MutableRow> mutable(List<ScoreRow> source) {
        var keys = new HashSet<List<Object>>();
        var result = new ArrayList<MutableRow>();
        for (var row : source) {
            if (!keys.add(List.of(row.tradeDate(), row.symbol())))
                throw new IllegalArgumentException(
                        "Duplicate observed key: " + row.tradeDate() + "/" + row.symbol());
            for (var value : row.values().values())
                if (value instanceof Number n && Double.isInfinite(n.doubleValue()))
                    throw new IllegalArgumentException("Infinite source observation");
            result.add(new MutableRow(row));
        }
        return result;
    }

    static final class MutableRow {
        final LocalDate date;
        final String symbol;
        final Map<String, Object> values;

        MutableRow(ScoreRow row) {
            date = row.tradeDate();
            symbol = row.symbol();
            values = new MutableScalarValues(row.values());
        }

        double n(String key) {
            return number(values.get(key));
        }

        double f(String key, double fallback) {
            return fill(n(key), fallback);
        }

        String s(String key) {
            return Objects.toString(values.get(key), "");
        }

        boolean b(String key) {
            return flag(values.get(key));
        }

        void put(String key, Object value) {
            values.put(key, value);
        }

        ScoreRow freeze() {
            return new ScoreRow(date, symbol, values);
        }
    }
}
