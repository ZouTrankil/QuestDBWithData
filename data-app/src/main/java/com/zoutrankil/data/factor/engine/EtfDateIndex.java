package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.*;

/** Shared date-to-panel-row positions; no copies of factor or source arrays. */
public final class EtfDateIndex {
    public record Position(int security,int row) {}
    private final NavigableMap<LocalDate,List<Position>> dates;
    public EtfDateIndex(List<EtfMarketPanel.Output> panel) {
        var indexed=new TreeMap<LocalDate,List<Position>>();
        var symbols=new HashSet<String>();
        for(int security=0;security<panel.size();security++) {
            var input=panel.get(security);
            if(!symbols.add(input.symbol()))throw new IllegalArgumentException("Duplicate ETF symbol");
            for(int row=0;row<input.dates().length;row++) {
                var date=input.dates()[row];
                if(date==null || row>0 && !date.isAfter(input.dates()[row-1]))
                    throw new IllegalArgumentException("Increasing unique ETF dates required");
                indexed.computeIfAbsent(date,ignored->new ArrayList<>()).add(new Position(security,row));
            }
        }
        indexed.replaceAll((date,positions)->List.copyOf(positions));
        dates=Collections.unmodifiableNavigableMap(indexed);
    }
    public NavigableMap<LocalDate,List<Position>> dates() { return dates; }
}
