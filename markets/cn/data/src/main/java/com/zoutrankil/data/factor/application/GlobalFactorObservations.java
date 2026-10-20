package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.factor.domain.FactorDefinition;
import com.zoutrankil.data.factor.domain.GlobalFactorObservation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Maps raw producer output before any universe expansion or direction adjustment. */
public final class GlobalFactorObservations {
    private GlobalFactorObservations() {}

    public static GlobalFactorObservation raw(
            LocalDate date,
            String entityId,
            FactorDefinition definition,
            double rawValue,
            LocalDateTime availableAt,
            LocalDateTime computedAt,
            String coverageFlag,
            String explicitQuality,
            String l2SourceLineageDigest) {
        Objects.requireNonNull(date);
        Objects.requireNonNull(computedAt);
        Objects.requireNonNull(definition);
        LocalDateTime availability = availableAt == null ? date.atTime(19, 0) : availableAt;
        Double value = Double.isFinite(rawValue) ? rawValue : null;
        String coverage = normalized(coverageFlag);
        String explicit = normalized(explicitQuality);
        String quality = coverage.equals("ok") && value != null ? "complete" : "missing";
        if (Set.of("invalid", "coverage_failure", "coverage_anomaly").contains(coverage))
            quality = "invalid";
        if (availability.isAfter(computedAt)
                || Set.of("unavailable", "late", "disconnected", "missing_source")
                        .contains(coverage)) quality = "unavailable";
        if (explicit.equals("invalid") || explicit.equals("unavailable")) quality = explicit;
        return new GlobalFactorObservation(
                date,
                definition.assetType(),
                entityId,
                definition.factorId(),
                definition.version(),
                value,
                availability,
                quality,
                "GLOBAL",
                l2SourceLineageDigest);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
