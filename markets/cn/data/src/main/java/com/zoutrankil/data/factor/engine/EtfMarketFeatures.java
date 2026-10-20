package com.zoutrankil.data.factor.engine;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Python ETF _build_market temporal stage after reference filtering and unique date ordering.
 * Cross-ETF beta, style residuals and recovery ranking belong to the shared panel stage.
 */
public final class EtfMarketFeatures {
    private EtfMarketFeatures() {}

    public static Map<String, double[]> compute(double[] close, double[] amount) {
        Objects.requireNonNull(close);
        Objects.requireNonNull(amount);
        if (close.length != amount.length)
            throw new IllegalArgumentException("Aligned ETF price and amount required");
        var result = new LinkedHashMap<String, double[]>();
        var ret1 = change(close, 1);
        result.put("ret1", ret1);
        result.put("ret5", change(close, 5));
        result.put("ret20", change(close, 20));
        var amount5 = FactorSeries.rolling(amount, 5, 3, false);
        var amount20 = FactorSeries.rolling(amount, 20, 10, false);
        result.put("amount5", amount5);
        result.put("amount20", amount20);
        result.put(
                "amount_ratio_5d_20d",
                FactorSeries.combine(amount5, amount20, FactorSeries::ratio));
        result.put("vol5", FactorSeries.rolling(ret1, 5, 3, true));
        result.put("vol20", FactorSeries.rolling(ret1, 20, 10, true));
        var high = extreme(close, 20, 10, true);
        var drawdown = FactorSeries.combine(close, high, (a, b) -> a / b - 1);
        result.put("drawdown20", drawdown);
        result.put("recent_drawdown20", extreme(drawdown, 10, 5, false));
        return Collections.unmodifiableMap(result);
    }

    private static double[] change(double[] values, int periods) {
        // Python pct_change(fill_method=None): preserve gaps and do not apply ratio's
        // small-denominator threshold.
        return FactorSeries.combine(
                values, FactorSeries.shift(values, periods), (a, b) -> a / b - 1);
    }

    private static double[] extreme(double[] values, int window, int minimum, boolean maximum) {
        double[] result = new double[values.length];
        var queue = new ArrayDeque<Integer>();
        int count = 0;
        for (int i = 0; i < values.length; i++) {
            if (i >= window && Double.isFinite(values[i - window])) count--;
            while (!queue.isEmpty() && queue.getFirst() <= i - window) queue.removeFirst();
            if (Double.isFinite(values[i])) {
                count++;
                while (!queue.isEmpty()
                        && (maximum
                                ? values[queue.getLast()] <= values[i]
                                : values[queue.getLast()] >= values[i])) queue.removeLast();
                queue.addLast(i);
            }
            result[i] = count < minimum ? Double.NaN : values[queue.getFirst()];
        }
        return result;
    }
}
