package com.zoutrankil.data.stock.domain.policy;


import com.zoutrankil.data.domain.StockDetailInfo;
import java.util.*;

/** Bounded incremental merge for the non-deduplicating current-reference table. No missing-row deletion. */
public final class StockDetailInfoMerge {
    public static final int MAX_ROWS=10_000;
    private StockDetailInfoMerge() {}
    public record Result(List<StockDetailInfo> rows,List<StockDetailInfo> changedRows,
                         int sourceRows,int insertedRows,int updatedRows,int unchangedRows) {
        public Result { rows=List.copyOf(rows);changedRows=List.copyOf(changedRows); }
        public boolean requiresPublication() { return !changedRows.isEmpty(); }
    }
    public static Result merge(List<StockDetailInfo> existing,List<StockDetailInfo> incoming) {
        var merged=index(existing,"Existing target");var source=index(incoming,"Source");
        var changes=new ArrayList<StockDetailInfo>();int inserted=0,updated=0,unchanged=0;
        for(var row:source.values()) {
            var prior=merged.get(row.key());
            if(prior==null) { merged.put(row.key(),row);changes.add(row);inserted++; }
            else if(sameBusinessValues(prior,row)) {
                // A refreshed observation is not itself a business revision. Preserve the stored version timestamp.
                unchanged++;
            } else {
                // Observation clocks have mixed provenance in legacy rows and are not source revisions.
                // Serialized publication and exact target readback decide whether the change is accepted.
                merged.put(row.key(),row);changes.add(row);updated++;
            }
        }
        if(merged.size()>MAX_ROWS) throw new IllegalArgumentException("Merged stock details exceed bounded target budget");
        return new Result(new ArrayList<>(merged.values()),changes,source.size(),inserted,updated,unchanged);
    }
    private static NavigableMap<String,StockDetailInfo> index(List<StockDetailInfo> rows,String origin) {
        Objects.requireNonNull(rows);
        if(rows.size()>MAX_ROWS) throw new IllegalArgumentException(origin+" exceeds bounded row budget");
        var indexed=new TreeMap<String,StockDetailInfo>();
        for(var row:rows) {
            Objects.requireNonNull(row);
            if(indexed.putIfAbsent(row.key(),row)!=null) throw new IllegalArgumentException(origin+" has duplicate identity");
        }
        return indexed;
    }
    public static boolean sameBusinessValues(StockDetailInfo a,StockDetailInfo b) {
        return Objects.equals(a.tsCode(),b.tsCode())
                && Objects.equals(a.symbol(),b.symbol())
                && Objects.equals(a.name(),b.name())
                && Objects.equals(a.market(),b.market())
                && Objects.equals(a.exchange(),b.exchange())
                && Objects.equals(a.listStatus(),b.listStatus())
                && Objects.equals(a.listingDate(),b.listingDate())
                && Objects.equals(a.fullname(),b.fullname())
                && Objects.equals(a.enname(),b.enname())
                && Objects.equals(a.cnspell(),b.cnspell())
                && Objects.equals(a.area(),b.area())
                && Objects.equals(a.industry(),b.industry())
                && Objects.equals(a.currType(),b.currType())
                && Objects.equals(a.delistingDate(),b.delistingDate())
                && Objects.equals(a.isHs(),b.isHs())
                && Objects.equals(a.actName(),b.actName())
                && Objects.equals(a.actEntType(),b.actEntType());
    }
}
