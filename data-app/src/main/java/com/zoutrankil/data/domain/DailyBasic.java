package com.zoutrankil.data.domain;

import java.time.LocalDate;

/** One stock's unadjusted daily valuation/liquidity metrics as reported by Tushare. */
public record DailyBasic(
        String tsCode,
        LocalDate tradeDate,
        Double close,
        Double turnoverRate,
        Double turnoverRateF,
        Double volumeRatio,
        Double pe,
        Double peTtm,
        Double pb,
        Double ps,
        Double psTtm,
        Double dvRatio,
        Double dvTtm,
        Double totalShare,
        Double floatShare,
        Double freeShare,
        Double totalMv,
        Double circMv) {
    public DailyBasic {
        new DailyBasicKey(tsCode, tradeDate);
        for (Double value : new Double[]{close, turnoverRate, turnoverRateF, volumeRatio, pe, peTtm, pb,
                ps, psTtm, dvRatio, dvTtm, totalShare, floatShare, freeShare, totalMv, circMv}) {
            if (value != null && !Double.isFinite(value))
                throw new IllegalArgumentException("daily_basic numeric values must be finite");
        }
    }
    public DailyBasicKey key() { return new DailyBasicKey(tsCode, tradeDate); }
}
