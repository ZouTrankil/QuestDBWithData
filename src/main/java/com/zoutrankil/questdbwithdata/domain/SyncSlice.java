package com.zoutrankil.questdbwithdata.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Inclusive source window. Period end is a report-period label, not an announcement date. */
public record SyncSlice(LocalDate start, LocalDate end, String code, String category, LocalDate periodEnd) {
    public SyncSlice {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        if (end.isBefore(start)) throw new IllegalArgumentException("Reversed slice");
        if (code == null || category == null) throw new IllegalArgumentException("Use empty string for absent dimension");
    }
    public long days() { return ChronoUnit.DAYS.between(start, end) + 1; }
}
