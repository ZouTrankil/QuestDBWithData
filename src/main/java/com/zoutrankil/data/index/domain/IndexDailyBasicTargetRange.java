package com.zoutrankil.data.index.domain;

import java.time.LocalDate;

public record IndexDailyBasicTargetRange(LocalDate min, LocalDate max) {
    public IndexDailyBasicTargetRange { if ((min == null) != (max == null) || min != null && min.isAfter(max)) throw new IllegalArgumentException("Invalid D020 physical range"); }
}
