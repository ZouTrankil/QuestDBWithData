package com.zoutrankil.data.stock.domain;
import java.time.LocalDate;
public record DailyBasicTargetRange(LocalDate min, LocalDate max) {
    public DailyBasicTargetRange {
        if ((min == null) != (max == null) || min != null && min.isAfter(max))
            throw new IllegalArgumentException("Invalid daily_basic QuestDB target range");
    }
}
