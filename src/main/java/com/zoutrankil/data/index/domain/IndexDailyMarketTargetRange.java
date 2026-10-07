package com.zoutrankil.data.index.domain;

import java.time.LocalDate;

public record IndexDailyMarketTargetRange(LocalDate min, LocalDate max) {
    public IndexDailyMarketTargetRange { if ((min == null) != (max == null) || min != null && min.isAfter(max)) throw new IllegalArgumentException("Invalid D019 target range"); }
}
