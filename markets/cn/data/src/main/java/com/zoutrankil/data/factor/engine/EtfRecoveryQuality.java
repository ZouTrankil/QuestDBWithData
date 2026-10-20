package com.zoutrankil.data.factor.engine;

import java.util.Arrays;

/** Date-local Python ETF recovery score; ranks include ineligible ETFs before the final mask. */
public final class EtfRecoveryQuality {
    private EtfRecoveryQuality() {}

    public static double[] compute(
            double[] ret5,
            double[] amountRatio,
            double[] vol5,
            double[] vol20,
            double[] recentDrawdown,
            double[] componentBreadth) {
        int n = ret5.length;
        for (var values :
                new double[][] {amountRatio, vol5, vol20, recentDrawdown, componentBreadth})
            if (values.length != n)
                throw new IllegalArgumentException("Aligned ETF recovery cross-section required");
        double[] compression =
                FactorSeries.combine(vol5, vol20, (a, b) -> FactorSeries.ratio(-a, b));
        double[][] ranks = {
            rank(ret5), rank(amountRatio), rank(compression), rank(componentBreadth)
        };
        double[] result = new double[n];
        Arrays.fill(result, Double.NaN);
        for (int i = 0; i < n; i++)
            if (recentDrawdown[i] <= -0.05 && ret5[i] > 0)
                result[i] = (ranks[0][i] + ranks[1][i] + ranks[2][i] + ranks[3][i]) / 4;
        return result;
    }

    private static double[] rank(double[] values) {
        // pandas rank keeps +/- infinity; the ordinary stock normalization kernel intentionally
        // does not.
        double[] valid = Arrays.stream(values).filter(v -> !Double.isNaN(v)).sorted().toArray();
        double[] result = new double[values.length];
        Arrays.fill(result, Double.NaN);
        for (int i = 0; i < values.length; i++)
            if (!Double.isNaN(values[i])) {
                int low = 0, high = valid.length;
                while (low < high) {
                    int mid = (low + high) >>> 1;
                    if (valid[mid] < values[i]) low = mid + 1;
                    else high = mid;
                }
                int first = low;
                high = valid.length;
                while (low < high) {
                    int mid = (low + high) >>> 1;
                    if (valid[mid] <= values[i]) low = mid + 1;
                    else high = mid;
                }
                result[i] = (first + 1.0 + low) / (2 * valid.length);
            }
        return result;
    }
}
