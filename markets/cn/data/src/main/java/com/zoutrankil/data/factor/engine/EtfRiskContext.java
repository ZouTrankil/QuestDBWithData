package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** Python snapshot risk context. A changed end date can revise the entire last-100-date window. */
public final class EtfRiskContext {
    private EtfRiskContext() {}

    public record Row(
            LocalDate date,
            double averageCorrelation,
            double correlationZ,
            double breadth,
            double crossSectionVolatility,
            double liquidityRatio,
            String state) {}

    public record Result(
            List<String> selectedSymbols, List<LocalDate> selectionDates, List<Row> rows) {}

    public static Result compute(
            List<EtfMarketPanel.Input> inputs, List<EtfMarketPanel.Output> panel) {
        if (inputs.size() != panel.size())
            throw new IllegalArgumentException("Aligned ETF panel required");
        var index = new EtfDateIndex(panel);
        var dates = new ArrayList<>(index.dates().keySet());
        if (dates.size() > 100)
            dates = new ArrayList<>(dates.subList(dates.size() - 100, dates.size()));
        if (dates.isEmpty()) return new Result(List.of(), List.of(), List.of());
        var first = dates.getFirst();
        var ranked = new ArrayList<Integer>();
        var amounts = new HashMap<Integer, Double>();
        for (int s = 0; s < inputs.size(); s++) {
            var input = inputs.get(s);
            var section = panel.get(s);
            if (!input.symbol().equals(section.symbol())
                    || !Arrays.equals(input.dates(), section.dates()))
                throw new IllegalArgumentException("ETF input order differs from panel");
            double sum = 0;
            int count = 0;
            boolean present = false;
            for (int r = 0; r < input.dates().length; r++)
                if (!input.dates()[r].isBefore(first)) {
                    present = true;
                    double value = input.amount()[r];
                    if (!Double.isNaN(value)) {
                        sum += value;
                        count++;
                    }
                }
            if (present) {
                ranked.add(s);
                amounts.put(s, count == 0 ? Double.NaN : sum / count);
            }
        }
        ranked.sort(
                (a, b) -> {
                    double x = amounts.get(a), y = amounts.get(b);
                    int order =
                            Double.isNaN(x)
                                    ? (Double.isNaN(y) ? 0 : 1)
                                    : Double.isNaN(y) ? -1 : Double.compare(y, x);
                    return order != 0
                            ? order
                            : inputs.get(a).symbol().compareTo(inputs.get(b).symbol());
                });
        if (ranked.size() > 100) ranked = new ArrayList<>(ranked.subList(0, 100));
        var selected = new ArrayList<String>();
        var selectedPositions = new HashMap<Integer, Integer>();
        for (int s : ranked) {
            selectedPositions.put(s, selected.size());
            selected.add(inputs.get(s).symbol());
        }
        double[] breadth = new double[dates.size()],
                volatility = new double[dates.size()],
                totalAmount = new double[dates.size()];
        var pivot = new ArrayList<double[]>();
        var pivotDates = new ArrayList<Integer>();
        for (int d = 0; d < dates.size(); d++) {
            var positions = index.dates().get(dates.get(d));
            int up = 0, n = 0;
            double mean = 0, m2 = 0;
            double[] values = new double[selected.size()];
            Arrays.fill(values, Double.NaN);
            boolean any = false;
            for (var p : positions) {
                double value = panel.get(p.security()).columns().get("ret1")[p.row()];
                if (value > 0) up++;
                if (!Double.isNaN(value)) {
                    n++;
                    double delta = value - mean;
                    mean += delta / n;
                    m2 += delta * (value - mean);
                }
                double amount = inputs.get(p.security()).amount()[p.row()];
                if (!Double.isNaN(amount)) totalAmount[d] += amount;
                var column = selectedPositions.get(p.security());
                if (column != null) {
                    values[column] = value;
                    if (!Double.isNaN(value)) any = true;
                }
            }
            breadth[d] = (double) up / positions.size();
            volatility[d] = n < 2 ? Double.NaN : Math.sqrt(m2 / (n - 1));
            if (any) {
                pivot.add(values);
                pivotDates.add(d);
            }
        }
        double[] average = new double[pivot.size()];
        Arrays.fill(average, Double.NaN);
        for (int d = 11; d < pivot.size(); d++) {
            double sum = 0;
            int count = 0;
            for (int a = 0; a < selected.size(); a++)
                for (int b = a + 1; b < selected.size(); b++) {
                    double value = correlation(pivot, Math.max(0, d - 19), d, a, b);
                    if (Double.isFinite(value)) {
                        sum += value;
                        count++;
                    }
                }
            if (count > 0) average[d] = sum / count;
        }
        var baseline = FactorSeries.shift(average, 1);
        var mean = FactorSeries.rolling(baseline, 60, 20, false);
        var std = FactorSeries.rolling(baseline, 60, 20, true);
        var amount5 = FactorSeries.rolling(totalAmount, 5, 3, false);
        var amount20 = FactorSeries.rolling(totalAmount, 20, 10, false);
        var result = new ArrayList<Row>();
        for (int i = 0; i < pivot.size(); i++) {
            int d = pivotDates.get(i);
            double z = FactorSeries.ratio(average[i] - mean[i], std[i]);
            String state =
                    z > 1 && breadth[d] < .35
                            ? "stress"
                            : z > .5 || breadth[d] < .40 ? "watch" : "normal";
            result.add(
                    new Row(
                            dates.get(d),
                            average[i],
                            z,
                            breadth[d],
                            volatility[d],
                            FactorSeries.ratio(amount5[d], amount20[d]),
                            state));
        }
        return new Result(List.copyOf(selected), List.copyOf(dates), List.copyOf(result));
    }

    private static double correlation(List<double[]> rows, int start, int end, int a, int b) {
        int n = 0;
        double mx = 0, my = 0, xx = 0, yy = 0, xy = 0;
        for (int i = start; i <= end; i++) {
            double x = rows.get(i)[a], y = rows.get(i)[b];
            if (!Double.isFinite(x) || !Double.isFinite(y)) continue;
            n++;
            double dx = x - mx, dy = y - my;
            mx += dx / n;
            my += dy / n;
            xx += dx * (x - mx);
            yy += dy * (y - my);
            xy += dx * (y - my);
        }
        return n < 10 || xx <= 0 || yy <= 0
                ? Double.NaN
                : Math.max(-1, Math.min(1, xy / Math.sqrt(xx * yy)));
    }
}
