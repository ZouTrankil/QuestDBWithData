package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.ThsIndex;
import java.util.*;

/** Content-incremental directory merge; observation clocks never decide which business values win. */
public final class ThsIndexMerge {
    public static final int MAX_ROWS=5000;
    public record Result(List<ThsIndex> rows,List<ThsIndex> changedRows,int inserted,int revised,int unchanged,int retainedAbsent,int removed) {
        public Result { rows=List.copyOf(rows);changedRows=List.copyOf(changedRows); }
        public boolean requiresWrite() { return !changedRows.isEmpty() || removed>0; }
    }
    private ThsIndexMerge() {}
    public static Result merge(List<ThsIndex> existing,List<ThsIndex> incoming,ThsIndexSource.Scope scope) {
        Objects.requireNonNull(scope);
        incoming.forEach(scope::requireContains);
        boolean complete=scope.code()==null && scope.exchange()==null && scope.type()==null;
        return merge(existing,incoming,complete);
    }
    /** Explicit prepared rows are bounded updates; omitted keys are never deletion instructions. */
    public static Result mergePrepared(List<ThsIndex> existing,List<ThsIndex> incoming) {
        return merge(existing,incoming,false);
    }
    private static Result merge(List<ThsIndex> existing,List<ThsIndex> incoming,boolean complete) {
        var old=index(existing);var source=index(incoming);
        // A large shrink of a declared complete observation is not silently accepted as a complete refresh.
        if(complete && source.isEmpty())
            throw new IllegalStateException("Empty complete THS directory cannot establish completeness");
        if(complete && source.size()*100L<old.size()*98L)
            throw new IllegalStateException("Complete THS directory shrank by more than 2%; completeness must be reconciled");
        // INCREMENTAL remains an upsert even when the provider observation covers its whole directory.
        // Missing source identities are not deletion instructions (user migration contract).
        var result=new TreeMap<>(old);
        var changed=new ArrayList<ThsIndex>();int inserted=0,revised=0,unchanged=0;
        for(var row:source.values()) {
            var prior=old.get(row.tsCode());
            if(prior==null) { inserted++;result.put(row.tsCode(),row);changed.add(row); }
            else if(sameBusinessValues(prior,row)) { unchanged++;result.put(row.tsCode(),prior); }
            else { revised++;result.put(row.tsCode(),row);changed.add(row); }
        }
        if(result.size()>MAX_ROWS) throw new IllegalArgumentException("Merged THS directory exceeds bounded row budget");
        int absent=(int)old.keySet().stream().filter(key->!source.containsKey(key)).count();
        return new Result(new ArrayList<>(result.values()),changed,inserted,revised,unchanged,
                absent,0);
    }
    public static boolean sameBusinessValues(ThsIndex a,ThsIndex b) {
        return a!=null && b!=null && a.tsCode().equals(b.tsCode()) && Objects.equals(a.name(),b.name())
                && Objects.equals(a.memberCount(),b.memberCount()) && Objects.equals(a.exchange(),b.exchange())
                && Objects.equals(a.listingDate(),b.listingDate()) && Objects.equals(a.indexType(),b.indexType());
    }
    private static TreeMap<String,ThsIndex> index(List<ThsIndex> rows) {
        if(rows.size()>MAX_ROWS) throw new IllegalArgumentException("THS directory exceeds row budget");
        var indexed=new TreeMap<String,ThsIndex>();
        for(var row:rows) if(indexed.putIfAbsent(row.tsCode(),row)!=null)
            throw new IllegalArgumentException("Duplicate THS natural identity");
        return indexed;
    }
}
