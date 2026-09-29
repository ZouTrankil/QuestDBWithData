package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Complete fund_portfolio identity; report period and disclosure date are distinct key dimensions. */
public record EtfPortfolioKey(String tsCode, LocalDate annDate, LocalDate endDate, String symbol) {
    public EtfPortfolioKey {
        requireCode(tsCode, "fund");
        Objects.requireNonNull(annDate, "announcement calendar date required");
        Objects.requireNonNull(endDate, "report-period calendar date required");
        requireCode(symbol, "holding security");
        if (endDate.isAfter(annDate))
            throw new IllegalArgumentException("Portfolio report period cannot be after its announcement date");
    }

    private static void requireCode(String value, String label) {
        if (value == null || !value.matches("[A-Za-z0-9]{1,16}\\.[A-Z]{2,3}"))
            throw new IllegalArgumentException("Complete " + label + " code with exchange suffix required");
    }
}
