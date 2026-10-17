package com.zoutrankil.data.domain;

import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Tushare ETF holdings row; numeric values retain provider units and updateTime is frozen UTC observation metadata. */
public record EtfPortfolio(EtfPortfolioKey key, Double mkv, Double amount,
                            Double stkMkvRatio, Double stkFloatRatio, Instant updateTime) {
    public EtfPortfolio {
        Objects.requireNonNull(key, "complete fund, announcement, report-period and holding key required");
        Objects.requireNonNull(updateTime, "frozen Java observation time required");
        TemporalValues.requirePrecision(updateTime, TemporalValues.Precision.MICROS);
        for (Double value : new Double[]{mkv, amount, stkMkvRatio, stkFloatRatio})
            if (value != null && !Double.isFinite(value))
                throw new IllegalArgumentException("fund_portfolio numeric values must be finite or null");
    }
    public String tsCode() { return key.tsCode(); }
    public LocalDate annDate() { return key.annDate(); }
    public LocalDate endDate() { return key.endDate(); }
    public String symbol() { return key.symbol(); }
}
