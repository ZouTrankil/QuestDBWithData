package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Typed one-row-per-DC-board/day business object, preserving source numeric units. */
public record DcIndex(DcIndexKey key, String name, String leading, String leadingCode,
        Double pctChange, Double leadingPct, Double totalMv, Double turnoverRate,
        Integer upNum, Integer downNum) {
    public DcIndex {
        Objects.requireNonNull(key, "Complete dc_index business key required");
        finite(pctChange, "pct_change"); finite(leadingPct, "leading_pct");
        finite(totalMv, "total_mv"); finite(turnoverRate, "turnover_rate");
        if (upNum != null && upNum < 0 || downNum != null && downNum < 0)
            throw new IllegalArgumentException("Board breadth counts cannot be negative");
    }
    public DcIndex(String tsCode, LocalDate tradeDate, String name, String leading, String leadingCode,
            Double pctChange, Double leadingPct, Double totalMv, Double turnoverRate, Integer upNum, Integer downNum) {
        this(new DcIndexKey(tsCode, tradeDate), name, leading, leadingCode,
                pctChange, leadingPct, totalMv, turnoverRate, upNum, downNum);
    }
    public String tsCode() { return key.tsCode(); }
    public LocalDate tradeDate() { return key.tradeDate(); }
    private static void finite(Double value, String field) {
        if (value != null && !Double.isFinite(value)) throw new IllegalArgumentException("Non-finite dc_index value: " + field);
    }
}
