package com.zoutrankil.data.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Current fund_basic listing state; updateTime is this run's frozen observation time. */
public record EtfBasic(
        EtfBasicKey key,
        String name,
        String management,
        String custodian,
        String fundType,
        LocalDate foundDate,
        LocalDate dueDate,
        LocalDate listDate,
        LocalDate issueDate,
        LocalDate delistDate,
        Double issueAmount,
        Double managementFee,
        Double custodianFee,
        Double durationYear,
        Double parValue,
        Double minimumAmount,
        Double expectedReturn,
        String benchmark,
        String status,
        String investType,
        String type,
        String trustee,
        LocalDate purchaseStartDate,
        LocalDate redemptionStartDate,
        String market,
        Instant updateTime) {
    public EtfBasic {
        Objects.requireNonNull(key, "complete fund code and technical key required");
        Objects.requireNonNull(updateTime, "observation time required");
        com.zoutrankil.data.domain.temporal.TemporalValues.requirePrecision(
                updateTime, com.zoutrankil.data.domain.temporal.TemporalValues.Precision.MICROS);
        if (!"E".equals(market)) throw new IllegalArgumentException("Only the frozen fund_basic market=E scope is admitted");
        for (Double value : new Double[]{issueAmount, managementFee, custodianFee, durationYear,
                parValue, minimumAmount, expectedReturn}) {
            if (value != null && !Double.isFinite(value)) throw new IllegalArgumentException("Finite fund_basic numeric value required");
        }
    }
    public String tsCode() { return key.tsCode(); }
    public Instant timestamp() { return key.timestamp(); }
}
