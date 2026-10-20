package com.zoutrankil.data.derived.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;

public final class RegimeFeaturesMonitorDailySourceData {
    private RegimeFeaturesMonitorDailySourceData() {}

    public record Margins(
            SortedMap<LocalDate, Double> balances,
            NavigableMap<LocalDate, Map<String, Long>> exchanges) {}
}
