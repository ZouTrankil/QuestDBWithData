package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.*;

/** Python latest_constituents normalization. Snapshot/as-of selection belongs to the source adapter. */
public final class EtfConstituentWeights {
    private EtfConstituentWeights() {}
    public record Input(String etf,String stock,double marketValueRatio,double marketValue,LocalDate announced,LocalDate periodEnd) {}
    public record Member(String etf,String stock,double weight,LocalDate announced,LocalDate periodEnd) {}
    private record Key(String etf,String stock) {}
    public static List<Member> normalize(List<Input> source,Set<String> validEtfs) {
        var rows=source.stream().filter(row->validEtfs.contains(row.etf())).toList();
        var counts=new HashMap<String,Integer>();var totals=new HashMap<String,Double>();
        for(var row:rows) {
            if(row.etf()==null || row.etf().isBlank() || row.stock()==null || row.stock().isBlank())
                throw new IllegalArgumentException("ETF and component identities required");
            counts.merge(row.etf(),1,Integer::sum);double raw=raw(row);
            if(!Double.isNaN(raw))totals.merge(row.etf(),raw,Double::sum);
        }
        var result=new LinkedHashMap<Key,Member>();
        for(var row:rows) {
            double total=totals.getOrDefault(row.etf(),0.0);
            double weight=total>0?raw(row)/total:Double.NaN;
            if(Double.isNaN(weight))weight=1.0/counts.get(row.etf());
            // Normalize before deduplicating, exactly as Python; do not renormalize the surviving rows.
            var key=new Key(row.etf(),row.stock());result.remove(key);
            result.put(key,new Member(row.etf(),row.stock(),weight,row.announced(),row.periodEnd()));
        }
        return List.copyOf(result.values());
    }
    private static double raw(Input row) {
        double ratio=Math.max(0,row.marketValueRatio());
        return ratio>0?ratio:Math.max(0,row.marketValue());
    }
}
