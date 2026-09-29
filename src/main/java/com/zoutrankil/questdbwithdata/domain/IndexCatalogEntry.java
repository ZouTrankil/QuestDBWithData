package com.zoutrankil.questdbwithdata.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** One row of the historical CSV-backed index catalog. Import time is lineage, not a business date. */
public record IndexCatalogEntry(String indexCode, String shortName, String fullName,
        LocalDate baseDate, Double basePoint, String series, Double sampleCount,
        Double latestClose, Double return1m, String assetClass, String hotspot,
        String currency, String cooperation, String trackingProduct,
        String complianceStatus, String category, LocalDate publishDate,
        Instant importTime) {
    public IndexCatalogEntry {
        if (indexCode == null || !indexCode.matches("[A-Za-z0-9]{6}"))
            throw new IllegalArgumentException("Six-character alphanumeric catalog code required");
        Objects.requireNonNull(baseDate, "Index base date required");
        Objects.requireNonNull(publishDate, "Index publication date required");
        Objects.requireNonNull(importTime, "Import observation required");
        if (importTime.getNano() % 1000 != 0)
            throw new IllegalArgumentException("Import observation exceeds storage microseconds");
        for (Double value : new Double[]{basePoint, sampleCount, latestClose, return1m}) {
            if (value != null && !Double.isFinite(value))
                throw new IllegalArgumentException("Nonfinite index catalog numeric value");
        }
        if (sampleCount != null && (sampleCount < 0 || sampleCount != Math.rint(sampleCount)))
            throw new IllegalArgumentException("Sample count must be a nonnegative whole number");
    }
}
