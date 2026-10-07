package com.zoutrankil.data.index.domain.policy;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.IndexCatalogEntry;
import java.util.*;

/** Content revisions from an explicitly selected file; absence is never a deletion instruction. */
public final class IndexCatalogMerge {
    private IndexCatalogMerge() {}
    public record Result(List<IndexCatalogEntry> rows,List<IndexCatalogEntry> changedRows,
                         int inserted,int revised,int unchanged,int retainedAbsent) {
        public Result { rows=List.copyOf(rows);changedRows=List.copyOf(changedRows); }
        public boolean requiresWrite() { return !changedRows.isEmpty(); }
    }
    public static Result merge(List<IndexCatalogEntry> existing,List<IndexCatalogEntry> source) {
        var target=index(existing);var incoming=index(source);var changed=new ArrayList<IndexCatalogEntry>();
        int inserted=0,revised=0,unchanged=0;
        int retained=(int)target.keySet().stream().filter(key->!incoming.containsKey(key)).count();
        for(var entry:incoming.entrySet()) {
            var prior=target.get(entry.getKey());var next=entry.getValue();
            if(prior!=null && sameBusinessValues(prior,next)) { unchanged++;continue; }
            if(prior==null) inserted++;else revised++;
            target.put(entry.getKey(),next);changed.add(next);
        }
        if(target.size()>com.zoutrankil.data.domain.IndexCatalogDataset.MAX_ROWS) throw new IllegalArgumentException("Merged catalog exceeds row bound");
        return new Result(new ArrayList<>(target.values()),changed,inserted,revised,unchanged,retained);
    }
    private static NavigableMap<String,IndexCatalogEntry> index(List<IndexCatalogEntry> rows) {
        if(rows.size()>com.zoutrankil.data.domain.IndexCatalogDataset.MAX_ROWS) throw new IllegalArgumentException("Catalog exceeds row bound");
        var result=new TreeMap<String,IndexCatalogEntry>();
        for(var row:rows) if(result.putIfAbsent(row.indexCode(),row)!=null)
            throw new IllegalArgumentException("Duplicate catalog business identity");
        return result;
    }
    public static boolean sameBusinessValues(IndexCatalogEntry left,IndexCatalogEntry right) {
        var a=new LinkedHashMap<>(IndexCatalogValues.values(left).asMap());
        var b=new LinkedHashMap<>(IndexCatalogValues.values(right).asMap());a.remove("import_time");b.remove("import_time");return a.equals(b);
    }
}
