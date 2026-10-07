package com.zoutrankil.data.flow.domain;

import java.time.LocalDate;

public record MoneyflowThsTargetRange(LocalDate min, LocalDate max) {
        public MoneyflowThsTargetRange {
            if ((min == null) != (max == null) || min != null && min.isAfter(max))
                throw new IllegalArgumentException("Invalid D025 physical target range");
        }
        public boolean empty() { return min == null; }
    }
