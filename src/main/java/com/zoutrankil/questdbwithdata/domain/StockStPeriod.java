package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Effective name interval from Tushare namechange; null endDate means still active at the frozen horizon. */
public record StockStPeriod(String tsCode, String name, LocalDate startDate, LocalDate endDate) {
    public StockStPeriod {
        // namechange can contain legacy/non-security identifiers on non-ST names (for example
        // X19363.SH). Python filters to ST names before applying the stricter daily-stock code rule.
        if (tsCode == null || !tsCode.matches("[A-Z0-9]{6}\\.(?:SH|SZ|BJ)"))
            throw new IllegalArgumentException("Six-character Tushare instrument code required for namechange history");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Namechange name required");
        Objects.requireNonNull(startDate, "Namechange start_date required");
        if (endDate != null && endDate.isBefore(startDate))
            throw new IllegalArgumentException("Namechange end_date precedes start_date");
    }
    public boolean isStName() { return name.toUpperCase(java.util.Locale.ROOT).contains("ST"); }
    public boolean overlaps(LocalDate from, LocalDate to) {
        return !startDate.isAfter(to) && (endDate == null || !endDate.isBefore(from));
    }
    public boolean activeOn(LocalDate day, LocalDate openEndedThrough) {
        LocalDate inclusiveEnd = endDate == null ? openEndedThrough : endDate;
        return !startDate.isAfter(day) && !inclusiveEnd.isBefore(day);
    }
}
