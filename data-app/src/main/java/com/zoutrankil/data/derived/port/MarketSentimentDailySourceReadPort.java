package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import java.time.*;
import java.util.*;
import com.zoutrankil.data.derived.domain.MarketSentimentDailySourceData.*;
/** Bounded streaming source contract; callback order is part of the original calculation protocol. */
public interface MarketSentimentDailySourceReadPort {
    interface PanelConsumer {void accept(PanelRow row);void completeDay(LocalDate date,Set<String> codes,int basic,int limits);}
    int readPanelMonth(LocalDate lower,LocalDate upper,Runnable checkCancellation,PanelConsumer consumer);
    Map<LocalDate,Set<String>> expectedDates(LocalDate from,LocalDate to);
    MarginData margins(LocalDate from,LocalDate to);Map<LocalDate,double[]> flows(LocalDate from,LocalDate to);
    String sourcePin(List<String> sources)throws Exception;
}
