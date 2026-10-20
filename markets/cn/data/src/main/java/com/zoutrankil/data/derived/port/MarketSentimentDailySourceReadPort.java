package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.derived.domain.MarketSentimentDailySourceData.MarginData;
import com.zoutrankil.data.derived.domain.MarketSentimentDailySourceData.PanelRow;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded streaming source contract; callback order is part of the original calculation protocol. */
public interface MarketSentimentDailySourceReadPort {
    interface PanelConsumer {
        void accept(PanelRow row);

        void completeDay(LocalDate date, Set<String> codes, int basic, int limits);

        default void stockScopeAudit(Map<String, Object> proof) {}
    }

    int readPanelMonth(
            LocalDate lower, LocalDate upper, Runnable checkCancellation, PanelConsumer consumer);

    Map<LocalDate, Set<String>> expectedDates(LocalDate from, LocalDate to);

    MarginData margins(LocalDate from, LocalDate to);

    Map<LocalDate, double[]> flows(LocalDate from, LocalDate to);

    String sourcePin(List<String> sources) throws Exception;
}
