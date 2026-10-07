package com.zoutrankil.batch.l2;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Native P8 computation with the canonical Python MainForceIntensityBuilder semantics. */
public final class L2IntensityFeatures {
    private L2IntensityFeatures() {}

    public static Map<String, Object> compute(L2FeatureData data, List<L2FeatureData.Wide> wide) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mfi_pulse", 0.0); out.put("mfi_escort", 0.0);
        out.put("mfi_precip", 0.0); out.put("mfi_score", 0.0);
        if (wide.isEmpty() || data.quotes().isEmpty()) return out;

        Map<String, double[]> sweeps = new LinkedHashMap<>();
        double totalVolume = 0, largeAmount = 0, largeVolume = 0;
        for (L2FeatureData.Wide row : wide) {
            var deal = row.deal;
            totalVolume += deal.volume();
            if (deal.side() != 0) continue;
            if (deal.buyId() != null) {
                double[] group = sweeps.computeIfAbsent(deal.buyId(), key -> new double[3]);
                group[0] += deal.amount(); group[1] += deal.volume(); group[2]++;
            }
            if (deal.amount() >= 1_000_000) { largeAmount += deal.amount(); largeVolume += deal.volume(); }
        }
        double sweepVolume = 0, sweepCount = 0; int groups = 0;
        for (double[] group : sweeps.values()) if (group[2] >= 3 && group[0] >= 500_000) {
            sweepVolume += group[1]; sweepCount += group[2]; groups++;
        }
        double pulse = totalVolume > 0 && groups > 0 ? sweepVolume / totalVolume * Math.log1p(sweepCount / groups) : 0;
        double bidSum = 0, askSum = 0, cumulativeOfi = 0;
        double previousBid = 0, previousAsk = 0, previousTotalBid = 0, previousTotalAsk = 0;
        Map<Long, List<Double>> windowOfi = new TreeMap<>();
        int index = 0;
        for (var quote : data.quotes()) {
            double bid = 0, ask = 0;
            for (int level = 0; level < 3; level++) if (!Double.isNaN(quote.bidVolume()[level])) bid += quote.bidVolume()[level];
            for (int level = 0; level < 5; level++) if (!Double.isNaN(quote.askVolume()[level])) ask += quote.askVolume()[level];
            bidSum += bid; askSum += ask;
            if (index > 0) cumulativeOfi += bid - previousBid - (ask - previousAsk);
            double ofi = index == 0 ? 0 : quote.totalBidVolume() - previousTotalBid - (quote.totalAskVolume() - previousTotalAsk);
            windowOfi.computeIfAbsent(quote.time() / 300_000 * 300_000, key -> new java.util.ArrayList<>()).add(Double.isNaN(ofi) ? 0 : ofi);
            previousBid = bid; previousAsk = ask;
            previousTotalBid = quote.totalBidVolume(); previousTotalAsk = quote.totalAskVolume(); index++;
        }
        double escort = askSum > 0 ? bidSum / askSum * 0.95 : 0;
        double precip = 0;
        if (largeVolume > 0) {
            double vwap = largeAmount / largeVolume;
            if (vwap > 0) {
                double premium = (data.quotes().getLast().price() - vwap) / vwap;
                // Python max(0.0, NaN) retains its first argument, so a missing
                // close price contributes no premium rather than poisoning MFI.
                premium = premium > 0 ? premium : 0.0;
                double normalizedOfi = Math.tanh(cumulativeOfi / 1_000_000);
                if (normalizedOfi > 0) precip = normalizedOfi * premium * 100;
            }
        }
        if (data.quotes().size() > 10) {
            double slopes = 0; int nSlopes = 0;
            for (List<Double> ofi : windowOfi.values()) {
                if (ofi.size() < 30) continue;
                double cumulative = 0, numerator = 0;
                double xMean = (ofi.size() - 1) / 2.0;
                for (int i = 0; i < ofi.size(); i++) { cumulative += ofi.get(i); numerator += (i - xMean) * cumulative; }
                double denominator = ofi.size() * ((double) ofi.size() * ofi.size() - 1) / 12;
                slopes += numerator / denominator; nSlopes++;
            }
            out.put("ofi_slope", nSlopes == 0 ? 0.0 : Math.tanh(slopes / nSlopes / 1000));
        }
        out.put("mfi_pulse", pulse); out.put("mfi_escort", escort); out.put("mfi_precip", precip);
        double score = 0.4 * Math.tanh(pulse * 10) + 0.3 * Math.tanh(escort / 2) + 0.3 * Math.tanh(precip);
        out.put("mfi_score", score > 0 ? score : 0.0);
        return out;
    }
}
