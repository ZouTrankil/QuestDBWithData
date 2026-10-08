package com.zoutrankil.data.domain;

import java.time.YearMonth;
import java.util.Objects;

/** D103 monthly period values: source returns retain stored units; spreads use the same stored units. */
public record EquityStyleMonthly(
        YearMonth month,
        Double hs300Ret1m,
        Double zz500Ret1m,
        Double allARet1m,
        Double cs1000Ret1m,
        Double smallLargeRet1m,
        Double midLargeRet1m,
        Double growthRet1m,
        Double valueRet1m,
        Double growthValueRet1m,
        Double energyRet1m,
        Double materialsRet1m,
        Double industrialsRet1m,
        Double consumerDiscretionaryRet1m,
        Double consumerStaplesRet1m,
        Double healthcareRet1m,
        Double financialsRet1m,
        Double itRet1m,
        Double telecomRet1m,
        Double utilitiesRet1m,
        Double energyVsAllA1m,
        Double materialsVsAllA1m,
        Double industrialsVsAllA1m,
        Double consumerDiscretionaryVsAllA1m,
        Double consumerStaplesVsAllA1m,
        Double healthcareVsAllA1m,
        Double financialsVsAllA1m,
        Double itVsAllA1m,
        Double telecomVsAllA1m,
        Double utilitiesVsAllA1m) {
    public EquityStyleMonthly {
        new EquityStyleMonthlyKey(Objects.requireNonNull(month, "month required"));
        finite(hs300Ret1m, "hs300_ret_1m");
        finite(zz500Ret1m, "zz500_ret_1m");
        finite(allARet1m, "all_a_ret_1m");
        finite(cs1000Ret1m, "cs1000_ret_1m");
        finite(smallLargeRet1m, "small_large_ret_1m");
        finite(midLargeRet1m, "mid_large_ret_1m");
        finite(growthRet1m, "growth_ret_1m");
        finite(valueRet1m, "value_ret_1m");
        finite(growthValueRet1m, "growth_value_ret_1m");
        finite(energyRet1m, "energy_ret_1m");
        finite(materialsRet1m, "materials_ret_1m");
        finite(industrialsRet1m, "industrials_ret_1m");
        finite(consumerDiscretionaryRet1m, "consumer_discretionary_ret_1m");
        finite(consumerStaplesRet1m, "consumer_staples_ret_1m");
        finite(healthcareRet1m, "healthcare_ret_1m");
        finite(financialsRet1m, "financials_ret_1m");
        finite(itRet1m, "it_ret_1m");
        finite(telecomRet1m, "telecom_ret_1m");
        finite(utilitiesRet1m, "utilities_ret_1m");
        finite(energyVsAllA1m, "energy_vs_all_a_1m");
        finite(materialsVsAllA1m, "materials_vs_all_a_1m");
        finite(industrialsVsAllA1m, "industrials_vs_all_a_1m");
        finite(consumerDiscretionaryVsAllA1m, "consumer_discretionary_vs_all_a_1m");
        finite(consumerStaplesVsAllA1m, "consumer_staples_vs_all_a_1m");
        finite(healthcareVsAllA1m, "healthcare_vs_all_a_1m");
        finite(financialsVsAllA1m, "financials_vs_all_a_1m");
        finite(itVsAllA1m, "it_vs_all_a_1m");
        finite(telecomVsAllA1m, "telecom_vs_all_a_1m");
        finite(utilitiesVsAllA1m, "utilities_vs_all_a_1m");
    }
    public EquityStyleMonthlyKey key() { return new EquityStyleMonthlyKey(month); }
    private static void finite(Double value, String field) {
        if (value != null && !Double.isFinite(value))
            throw new IllegalArgumentException("Finite nullable DOUBLE required: " + field);
    }
}

