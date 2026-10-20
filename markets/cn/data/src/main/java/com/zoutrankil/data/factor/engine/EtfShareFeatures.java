package com.zoutrankil.data.factor.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** ETF share inputs must first be left-joined onto observed market dates, matching Python. */
public final class EtfShareFeatures {
    private EtfShareFeatures() {}

    public static Map<String, double[]> compute(double[] close, double[] reportedShare) {
        if (close.length != reportedShare.length)
            throw new IllegalArgumentException("Aligned ETF share inputs required");
        var share = FactorSeries.forwardFill(reportedShare);
        var change = FactorSeries.combine(share, FactorSeries.shift(share, 5), (a, b) -> a / b - 1);
        var delta = FactorSeries.combine(share, FactorSeries.shift(share, 1), (a, b) -> a - b);
        var flow = FactorSeries.combine(delta, close, (a, b) -> a * b / 10000);
        var aum = FactorSeries.combine(share, close, (a, b) -> a * b / 10000);
        double[] sum = new double[close.length];
        for (int i = 0; i < sum.length; i++) {
            int count = 0;
            double value = 0;
            for (int j = Math.max(0, i - 4); j <= i; j++)
                if (Double.isFinite(flow[j])) {
                    count++;
                    value += flow[j];
                }
            sum[i] = count < 3 ? Double.NaN : value;
        }
        var positive = FactorSeries.map(flow, a -> Double.isNaN(a) ? Double.NaN : a > 0 ? 1 : 0);
        var result = new LinkedHashMap<String, double[]>();
        result.put("total_share", share);
        result.put("share_change_5d", change);
        result.put("share_delta", delta);
        result.put("net_flow_yi", flow);
        result.put("aum_yi", aum);
        result.put("positive_flow", positive);
        result.put("flow_intensity_5d", FactorSeries.combine(sum, aum, FactorSeries::ratio));
        result.put("flow_persistence_5d", FactorSeries.rolling(positive, 5, 3, false));
        return Collections.unmodifiableMap(result);
    }
}
