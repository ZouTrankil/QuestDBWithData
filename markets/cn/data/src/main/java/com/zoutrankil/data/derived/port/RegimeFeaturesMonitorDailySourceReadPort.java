package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;
import com.zoutrankil.data.domain.policy.RegimeFeaturesMonitorDailyCalculation.*;
import com.zoutrankil.data.derived.domain.RegimeFeaturesMonitorDailySourceData.*;
/** Source rows stream in their original cancellation, budget and hashing order. */
public interface RegimeFeaturesMonitorDailySourceReadPort {
    @FunctionalInterface interface PanelDayConsumer {void accept(LocalDate date,List<Stock> stocks,Set<String> codes,int basic,int limits);}
    void readPanelMonth(LocalDate lower,LocalDate upper,Set<LocalDate> calendar,long[] panelRows,MessageDigest hash,BooleanSupplier cancelled,PanelDayConsumer complete);
    void readValuations(LocalDate from,LocalDate upper,Set<LocalDate> calendar,Map<LocalDate,Valuation> result,long[] rawRows,MessageDigest hash,BooleanSupplier cancelled);
    NavigableSet<LocalDate> calendar(LocalDate from,LocalDate to,MessageDigest hash);
    SortedMap<LocalDate,Double> readNorthbound(LocalDate from,LocalDate to,MessageDigest hash);
    Margins readMargins(LocalDate from,LocalDate to,MessageDigest hash);
    Map<LocalDate,Double> readGov(LocalDate from,LocalDate to,MessageDigest hash);String sourcePin()throws Exception;
}
