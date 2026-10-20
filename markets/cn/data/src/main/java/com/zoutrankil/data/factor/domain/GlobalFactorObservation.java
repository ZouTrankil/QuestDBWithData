package com.zoutrankil.data.factor.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/** Physical factor_observation contract: raw, pool-independent values only. */
public record GlobalFactorObservation(
        LocalDate signalDate,
        String entityType,
        String entityId,
        String factorId,
        String definitionVersion,
        Double factorValue,
        LocalDateTime availableAt,
        String qualityStatus,
        String scope,
        String l2SourceLineageDigest) {
    public GlobalFactorObservation {
        Objects.requireNonNull(signalDate);
        Objects.requireNonNull(availableAt);
        for (String key : new String[] {entityType, entityId, factorId, definitionVersion})
            if (key == null || key.isBlank())
                throw new IllegalArgumentException("Blank observation key");
        if (!"GLOBAL".equals(scope))
            throw new IllegalArgumentException("Observation scope must be GLOBAL");
        if (!Set.of("complete", "missing", "invalid", "unavailable").contains(qualityStatus))
            throw new IllegalArgumentException("Unknown observation quality");
        if (factorValue != null && !Double.isFinite(factorValue))
            throw new IllegalArgumentException("Nonfinite raw observation");
        if ("complete".equals(qualityStatus) && factorValue == null)
            throw new IllegalArgumentException("Complete observation needs a raw value");
    }
}
