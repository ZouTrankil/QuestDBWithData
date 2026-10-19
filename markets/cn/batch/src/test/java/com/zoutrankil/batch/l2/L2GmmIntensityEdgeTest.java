package com.zoutrankil.batch.l2;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Numerical edge results captured from the canonical Python P7/P8 builders. */
class L2GmmIntensityEdgeTest {
    private static final long START = L2FeatureMath.OPEN;
    private static final LocalDate DATE = LocalDate.of(2026, 9, 24);

    @Test void infiniteMinuteSpreadReturnsCanonicalEmptyGmmMetrics() {
        Map<String, Object> result = assertDoesNotThrow(() -> L2GmmFeatures.compute(gmmData(-1e-6, 10.02)));
        assertEquals(Map.of("gmm_main_force_ratio", 0.0, "gmm_hft_ratio", 0.0,
                "gmm_retail_ratio", 0.0, "gmm_main_force_net_inflow", 0.0), result);
    }

    @Test void missingSpreadIsFilledWithZeroAndStillFitsGmm() {
        Map<String, Object> result = L2GmmFeatures.compute(gmmData(Double.NaN, Double.NaN));
        assertEquals(0.52, (double) result.get("gmm_main_force_ratio"), 1e-12);
        assertEquals(0.48, (double) result.get("gmm_hft_ratio"), 1e-12);
        assertEquals(0.0, result.get("gmm_retail_ratio"));
        assertEquals(-3900.0, (double) result.get("gmm_main_force_net_inflow"), 1e-9);
    }

    @Test void missingClosingPriceContributesZeroPrecipitationAndKeepsFiniteMfiScore() {
        var deal = new L2FeatureData.Deal(START, 10, 100000, 0, "1", "1", "2");
        var quotes = List.of(quote(START, 10, 10, 10.02, 100, 20),
                quote(START + 3000, Double.NaN, 10, 10.02, 2000000, 20));
        var data = new L2FeatureData("510300.SH", DATE, List.of(deal), List.of(), quotes);
        var wide = List.of(new L2FeatureData.Wide(deal, quotes.getFirst(), 1, Double.NaN, true));
        Map<String, Object> result = L2IntensityFeatures.compute(data, wide);
        assertEquals(0.0, result.get("mfi_pulse"));
        assertEquals(28501.425, (double) result.get("mfi_escort"), 1e-9);
        assertEquals(0.0, result.get("mfi_precip"));
        assertEquals(0.3, (double) result.get("mfi_score"), 1e-12);
        assertEquals(4, result.size(), "Two snapshots leave the canonical optional OFI slope unset");
    }

    private static L2FeatureData gmmData(double bid, double ask) {
        var deals = new ArrayList<L2FeatureData.Deal>();
        var orders = new ArrayList<L2FeatureData.Order>();
        var quotes = new ArrayList<L2FeatureData.Quote>();
        for (int i = 0; i < 6; i++) {
            long time = START + i * 60000;
            deals.add(new L2FeatureData.Deal(time, 10, 100 + i * 10, i % 2,
                    Integer.toString(i), "1", "2"));
            orders.add(new L2FeatureData.Order(time, 10, 100, 0, Integer.toString(i), false));
            quotes.add(quote(time, 10, bid, ask, 100, 20));
        }
        return new L2FeatureData("510300.SH", DATE, deals, orders, quotes);
    }

    private static L2FeatureData.Quote quote(long time, double price, double bid, double ask,
                                            double bidVolume, double askVolume) {
        double[] bp = new double[10], ap = new double[10], bv = new double[10], av = new double[10];
        Arrays.fill(bp, bid); Arrays.fill(ap, ask); Arrays.fill(bv, bidVolume); Arrays.fill(av, askVolume);
        return new L2FeatureData.Quote(time, 0, 0, price, 0, 0, 0, 0,
                bidVolume * 10, askVolume * 10, bid, ask, bp, ap, bv, av);
    }
}
