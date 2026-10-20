package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** CSI style warmup: market and daily-basic windows use their own observed-row sequences. */
public final class EtfCsiTemporal {
    private EtfCsiTemporal() {}

    public static Map<String, double[]> compute(
            LocalDate[] marketDates,
            double[] close,
            double[] amount,
            LocalDate[] basicDates,
            double[] turnover) {
        validate(marketDates, close, amount);
        validate(basicDates, turnover);
        var ret = FactorSeries.combine(close, FactorSeries.shift(close, 1), (a, b) -> a / b - 1);
        var annualReturn =
                FactorSeries.combine(close, FactorSeries.shift(close, 252), (a, b) -> a / b - 1);
        var annualVol = FactorSeries.rolling(ret, 252, 120, true);
        var basicMean = FactorSeries.rolling(turnover, 20, 10, false);
        double[] aligned = new double[marketDates.length];
        Arrays.fill(aligned, Double.NaN);
        int j = 0;
        for (int i = 0; i < marketDates.length; i++) {
            while (j < basicDates.length && basicDates[j].isBefore(marketDates[i])) j++;
            if (j < basicDates.length && basicDates[j].equals(marketDates[i]))
                aligned[i] = basicMean[j];
        }
        var result = new LinkedHashMap<String, double[]>();
        result.put("ret1", ret);
        result.put("return_252", annualReturn);
        result.put("volatility_252", annualVol);
        result.put("amount_20", FactorSeries.rolling(amount, 20, 10, false));
        result.put("turnover_20", aligned);
        return Collections.unmodifiableMap(result);
    }

    private static void validate(LocalDate[] dates, double[]... columns) {
        for (var column : columns)
            if (column.length != dates.length)
                throw new IllegalArgumentException("Aligned CSI temporal columns required");
        for (int i = 0; i < dates.length; i++)
            if (dates[i] == null || i > 0 && !dates[i].isAfter(dates[i - 1]))
                throw new IllegalArgumentException("Increasing unique CSI source dates required");
    }
}
