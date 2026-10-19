package com.zoutrankil.data.etf.domain;

import java.time.LocalDate;

/** Physical date bounds read from one configured ETF target. */
public record EtfTargetRange(LocalDate min, LocalDate max) {
    public EtfTargetRange {
        if ((min == null) != (max == null) || min != null && min.isAfter(max))
            throw new IllegalStateException("Invalid ETF physical date range");
    }
}
