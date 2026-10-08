package com.zoutrankil.data.index.domain;

import java.time.LocalDate;

public record IndexWeightTargetRange(LocalDate min, LocalDate max) {
    public IndexWeightTargetRange {
        if ((min == null) != (max == null) || min != null && min.isAfter(max))
            throw new IllegalArgumentException("Invalid D021 target date range");
    }
}
