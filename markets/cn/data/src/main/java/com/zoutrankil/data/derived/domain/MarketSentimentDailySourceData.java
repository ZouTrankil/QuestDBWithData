package com.zoutrankil.data.derived.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;

public final class MarketSentimentDailySourceData {
    private MarketSentimentDailySourceData() {}

    public record PanelRow(
            LocalDate date,
            String code,
            double close,
            double previous,
            double amount,
            double turnover,
            double circ,
            double mv,
            double pb,
            double up,
            double down,
            boolean suspended,
            boolean st) {}

    public record MarginData(
            NavigableMap<LocalDate, double[]> margins,
            NavigableMap<LocalDate, Map<String, Long>> exchanges) {}
}
