package com.zoutrankil.data.derived.domain;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import java.time.*;
import java.util.*;

public final class MarketSentimentDailySourceData {
    private MarketSentimentDailySourceData() {}
    public record PanelRow(LocalDate date,String code,double close,double previous,double amount,double turnover,
                           double circ,double mv,double pb,double up,double down,boolean suspended,boolean st) {}
    public record MarginData(NavigableMap<LocalDate,double[]> margins,NavigableMap<LocalDate,Map<String,Long>> exchanges) {}
}
