package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Daily valuation/capitalization metrics for a Tushare core index. */
public record IndexDailyBasic(IndexDailyBasicKey key, Double totalMv, Double floatMv,
        Double totalShare, Double floatShare, Double freeShare, Double turnoverRate,
        Double turnoverRateF, Double pe, Double peTtm, Double pb) {
    public IndexDailyBasic {
        Objects.requireNonNull(key, "Complete D020 business key required");
        if (!finite(totalMv) || !finite(floatMv) || !finite(totalShare) || !finite(floatShare)
                || !finite(freeShare) || !finite(turnoverRate) || !finite(turnoverRateF)
                || !finite(pe) || !finite(peTtm) || !finite(pb))
            throw new IllegalArgumentException("D020 metrics must be finite or null");
    }
    public IndexDailyBasic(String tsCode, LocalDate tradeDate, Double totalMv, Double floatMv,
            Double totalShare, Double floatShare, Double freeShare, Double turnoverRate,
            Double turnoverRateF, Double pe, Double peTtm, Double pb) {
        this(new IndexDailyBasicKey(tsCode, tradeDate), totalMv, floatMv, totalShare, floatShare,
                freeShare, turnoverRate, turnoverRateF, pe, peTtm, pb);
    }
    private static boolean finite(Double value) { return value == null || Double.isFinite(value); }
    public String tsCode() { return key.tsCode(); }
    public LocalDate tradeDate() { return key.tradeDate(); }
}
