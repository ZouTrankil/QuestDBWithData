package com.zoutrankil.data.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/** Current board membership. Observation time is not an upstream revision cursor. */
public record ThsMember(String boardCode, String constituentCode, String constituentName,
        Double weight, LocalDate inDate, LocalDate outDate, String isNew, Instant observedAt) {
    public record Key(String boardCode, String constituentCode) {}

    public ThsMember {
        if (!ThsIndex.validCode(boardCode)) throw new IllegalArgumentException("THS board code required");
        if (constituentCode == null || constituentCode.isBlank() || constituentCode.length() > 64)
            throw new IllegalArgumentException("Nonblank bounded provider constituent code required");
        if (constituentName == null || constituentName.isBlank())
            throw new IllegalArgumentException("Constituent name required");
        if (weight != null && !Double.isFinite(weight)) throw new IllegalArgumentException("Finite weight required");
        if (isNew != null && !Set.of("Y", "N").contains(isNew))
            throw new IllegalArgumentException("Y/N membership flag required when present");
        Objects.requireNonNull(observedAt, "Observation instant required");
        if (observedAt.getNano() % 1000 != 0) throw new IllegalArgumentException("Microsecond observation required");
        if (inDate != null && outDate != null && outDate.isBefore(inDate))
            throw new IllegalArgumentException("Membership end precedes start");
    }

    public Key key() { return new Key(boardCode, constituentCode); }
}
