package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.*;

/** CSI ETF style values with per-factor covered weight and the Python 60% admission threshold. */
public final class EtfCsiAggregation {
    private EtfCsiAggregation() {}
    public static final List<String> FACTORS=List.of("csi_value_reference","csi_growth_reference","csi_dividend_reference",
            "csi_quality_reference","csi_low_vol_reference","csi_momentum_reference","csi_cashflow_reference","csi_liquidity_reference");
    public record Stock(String symbol,Map<String,Double> factors) {}
    public record Row(LocalDate date,String etf,String factor,double value,double coverage,int componentCount,
            LocalDate announced,LocalDate periodEnd) {}
    public static List<Row> compute(LocalDate date,List<Stock> stocks,List<EtfConstituentWeights.Member> members) {
        Objects.requireNonNull(date);if(stocks.isEmpty() || members.isEmpty())return List.of();
        var indexed=new HashMap<String,Stock>();
        for(var stock:stocks)if(stock.symbol()==null || indexed.putIfAbsent(stock.symbol(),stock)!=null)
            throw new IllegalArgumentException("Unique CSI stock values required");
        var baskets=new TreeMap<String,List<EtfConstituentWeights.Member>>();var seen=new HashSet<List<String>>();
        for(var member:members) {
            if(member.announced()==null || member.periodEnd()==null || member.announced().isAfter(date)
                    || !Double.isFinite(member.weight()) || member.weight()<0 || !seen.add(List.of(member.etf(),member.stock())))
                throw new IllegalArgumentException("Unique available normalized CSI holdings required");
            baskets.computeIfAbsent(member.etf(),ignored->new ArrayList<>()).add(member);
        }
        var result=new ArrayList<Row>();
        for(var entry:baskets.entrySet()) {
            var first=entry.getValue().getFirst();
            for(var member:entry.getValue())if(!member.announced().equals(first.announced()) || !member.periodEnd().equals(first.periodEnd()))
                throw new IllegalArgumentException("CSI basket mixes disclosure versions");
            for(String factor:FACTORS) {
                double sum=0,coverage=0;
                for(var member:entry.getValue()) {
                    var stock=indexed.get(member.stock());var value=stock==null?null:stock.factors().get(factor);
                    if(value!=null && !Double.isNaN(value)){sum+=member.weight()*value;coverage+=member.weight();}
                }
                result.add(new Row(date,entry.getKey(),factor,coverage<.60?Double.NaN:sum/coverage,coverage,
                        entry.getValue().size(),first.announced(),first.periodEnd()));
            }
        }
        return List.copyOf(result);
    }
}
