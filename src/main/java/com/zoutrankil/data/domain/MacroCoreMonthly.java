package com.zoutrankil.data.domain;

import java.time.YearMonth;
import java.util.Objects;

/** D104 monthly observations in unchanged stored source units, including nullable historical rows. */
public record MacroCoreMonthly(YearMonth month, Double cpiYoy, Double ppiYoy, Double pmiMfg,
        Double gdpYoy, Double m2Yoy, Double socialFinancingStock, Double newRmbLoan,
        Double socialFinancingYoy) {
    public MacroCoreMonthly {
        new MacroCoreMonthlyKey(Objects.requireNonNull(month, "month required"));
        finite(cpiYoy, "cpi_yoy"); finite(ppiYoy, "ppi_yoy"); finite(pmiMfg, "pmi_mfg");
        finite(gdpYoy, "gdp_yoy"); finite(m2Yoy, "m2_yoy");
        finite(socialFinancingStock, "social_financing_stock"); finite(newRmbLoan, "new_rmb_loan");
        finite(socialFinancingYoy, "social_financing_yoy");
    }
    public MacroCoreMonthlyKey key() { return new MacroCoreMonthlyKey(month); }
    private static void finite(Double value, String field) {
        if (value != null && !Double.isFinite(value))
            throw new IllegalArgumentException("Finite nullable DOUBLE required: " + field);
    }
}
