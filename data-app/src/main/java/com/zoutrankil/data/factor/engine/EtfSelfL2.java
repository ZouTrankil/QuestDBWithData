package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.*;

/** Python _build_etf_l2_self projection after numeric conversion by the source reader. */
public final class EtfSelfL2 {
    private EtfSelfL2() {}
    public record Market(LocalDate date,String symbol,String name,String category) {}
    public record Input(LocalDate date,String symbol,double obi1,double ofi,double spread,double depth) {}
    public record Output(LocalDate date,String symbol,String name,String category,
            double obi1,double ofi,double spread,double depth) {}
    private record Key(LocalDate date,String symbol) {}

    public static List<Output> compute(List<Input> source,List<Market> market,Set<LocalDate> outputDates) {
        if(source.isEmpty() || market.isEmpty())return List.of();
        var valid=new HashSet<String>();var identities=new HashMap<Key,Market>();
        for(var row:market) {
            valid.add(row.symbol());
            identities.put(new Key(row.date(),row.symbol()),row);
        }
        var result=new ArrayList<Output>();
        for(var row:source) {
            if(!valid.contains(row.symbol()) || (outputDates!=null && !outputDates.isEmpty()
                    && !outputDates.contains(row.date())))continue;
            // Python filters symbols globally, then left joins date identity. Missing market
            // rows do not discard observed L2; repeated L2 rows retain their original order.
            var identity=identities.get(new Key(row.date(),row.symbol()));
            result.add(new Output(row.date(),row.symbol(),identity==null?null:identity.name(),
                    identity==null?null:identity.category(),row.obi1(),row.ofi(),row.spread(),row.depth()));
        }
        return List.copyOf(result);
    }
}
