package com.zoutrankil.data.stock.domain;

import java.time.LocalDate;

/** The physical bounds used by a stock planner; adapters retain their own validation errors. */
public record StockTargetRange(LocalDate min, LocalDate max) {}
