package com.zoutrankil.data.factor.engine;

import java.util.Arrays;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/** Primitive-array operations. NaN represents missing input, never an imputed zero. */
public final class FactorSeries {
    private FactorSeries() {}

    public static double[] map(double[] values, DoubleUnaryOperator operation) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) result[i] = operation.applyAsDouble(values[i]);
        return result;
    }

    public static double[] combine(double[] left, double[] right, DoubleBinaryOperator operation) {
        if (left.length != right.length)
            throw new IllegalArgumentException("Series lengths differ");
        double[] result = new double[left.length];
        for (int i = 0; i < left.length; i++)
            result[i] = operation.applyAsDouble(left[i], right[i]);
        return result;
    }

    public static double[] shift(double[] values, int periods) {
        if (periods < 0)
            throw new IllegalArgumentException("Forward-looking shifts are not features");
        double[] result = new double[values.length];
        Arrays.fill(result, Double.NaN);
        if (periods < values.length)
            System.arraycopy(values, 0, result, periods, values.length - periods);
        return result;
    }

    public static double[] forwardFill(double[] values) {
        double[] result = values.clone();
        double previous = Double.NaN;
        for (int i = 0; i < result.length; i++) {
            if (!Double.isNaN(result[i])) previous = result[i];
            else result[i] = previous;
        }
        return result;
    }

    /** pandas/Polars sample standard deviation (ddof=1), with row-based windows. */
    public static double[] rolling(double[] values, int window, int minimum, boolean std) {
        if (window < 1 || minimum < 1 || minimum > window)
            throw new IllegalArgumentException("Invalid rolling window");
        double[] result = new double[values.length];
        int count = 0;
        double mean = 0, m2 = 0;
        double lastFinite = Double.NaN;
        int identicalRun = 0;
        for (int i = 0; i < values.length; i++) {
            if (i >= window && Double.isFinite(values[i - window])) {
                double old = values[i - window];
                if (count == 1) {
                    count = 0;
                    mean = 0;
                    m2 = 0;
                } else {
                    double nextMean = mean + (mean - old) / (count - 1);
                    m2 -= (old - mean) * (old - nextMean);
                    mean = nextMean;
                    count--;
                }
            }
            double value = values[i];
            if (Double.isFinite(value)) {
                identicalRun = value == lastFinite ? identicalRun + 1 : 1;
                lastFinite = value;
                count++;
                double delta = value - mean;
                mean += delta / count;
                m2 += delta * (value - mean);
            }
            // Once every finite member is identical, reset remove/add roundoff.
            // Otherwise a constant price can acquire nonzero volatility and a
            // zero volume denominator can incorrectly produce a finite ratio.
            if (count > 0 && identicalRun >= count) {
                mean = lastFinite;
                m2 = 0;
            }
            result[i] =
                    count < minimum || (std && count < 2)
                            ? Double.NaN
                            : std ? Math.sqrt(Math.max(0, m2) / (count - 1)) : mean;
        }
        return result;
    }

    /** adjust=false, ignore_nulls=false, followed by forward-fill as in the Python kernel. */
    public static double[] ema(double[] values, int span) {
        if (span < 1) throw new IllegalArgumentException("Positive EMA span required");
        double alpha = 2.0 / (span + 1), weighted = Double.NaN, oldWeight = 1;
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            double current = values[i];
            if (Double.isNaN(weighted)) weighted = current;
            else {
                oldWeight *= 1 - alpha;
                if (!Double.isNaN(current)) {
                    weighted = (oldWeight * weighted + alpha * current) / (oldWeight + alpha);
                    oldWeight = 1;
                }
            }
            result[i] = weighted;
        }
        return result;
    }

    public static double ratio(double numerator, double denominator) {
        return Math.abs(denominator) > 1e-12 ? numerator / denominator : Double.NaN;
    }
}
