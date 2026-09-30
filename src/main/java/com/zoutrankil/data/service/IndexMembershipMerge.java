package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.IndexMembership;
import java.util.*;

/** Incremental membership-period updates. Neither source absence nor a later observation implies deletion. */
public final class IndexMembershipMerge {
    public static final int MAX_EXISTING_ROWS=100_000;
    public static final int MAX_SLICE_ROWS=4000;
    private static final Comparator<IndexMembership.Key> ORDER=Comparator.comparing(IndexMembership.Key::indexCode)
            .thenComparing(IndexMembership.Key::tsCode).thenComparing(IndexMembership.Key::membershipStartDate);
    public record Result(List<IndexMembership> rows,List<IndexMembership> changedRows,int inserted,int revised,
                         int unchanged,int retainedAbsent) {
        public Result { rows=List.copyOf(rows);changedRows=List.copyOf(changedRows); }
        public boolean requiresWrite() { return !changedRows.isEmpty(); }
    }
    private IndexMembershipMerge() {}
    public static Result mergeSource(List<IndexMembership> existing,List<IndexMembership> incoming,String l2Code) {
        if(l2Code==null || !l2Code.matches("[0-9]{6}\\.SI")) throw new IllegalArgumentException("Explicit L2 scope required");
        if(incoming.size()>=MAX_SLICE_ROWS) throw new IllegalArgumentException("Membership slice exceeds two bounded source responses");
        var before=index(existing);var source=index(incoming);var result=new TreeMap<>(before);
        var changes=new ArrayList<IndexMembership>();int inserted=0,revised=0,unchanged=0;
        for(var input:source.values()) {
            if(!input.indexCode().equals(l2Code) || !input.level().equals("L2"))
                throw new IllegalArgumentException("Membership outside frozen L2 scope");
            if(input.weight()!=null || input.constituentCode()!=null)
                throw new IllegalArgumentException("Membership endpoint supplies neither weight nor internal constituent code");
            var old=before.get(input.key());
            // These two columns belong to legacy/other sources; absence in this endpoint must not erase them.
            var row=old==null?input:new IndexMembership(input.indexCode(),input.tsCode(),input.observedAt(),input.indexName(),
                    old.constituentCode(),input.constituentName(),input.membershipStartDate(),input.membershipEndDate(),
                    input.latestFlag(),old.weight(),input.level(),input.l1Name(),input.l2Name(),input.l3Name());
            if(old==null) { inserted++;result.put(row.key(),row);changes.add(row); }
            else if(sameBusinessValues(old,row)) unchanged++;
            else { revised++;result.put(row.key(),row);changes.add(row); }
        }
        if(result.size()>MAX_EXISTING_ROWS) throw new IllegalArgumentException("Merged membership exceeds row budget");
        int absent=(int)before.keySet().stream()
                .filter(k->k.indexCode().equals(l2Code) && !source.containsKey(k)).count();
        return new Result(new ArrayList<>(result.values()),changes,inserted,revised,unchanged,absent);
    }
    /** Prepared input owns every supplied column; absence still does not delete a membership period. */
    public static Result mergePrepared(List<IndexMembership> existing,List<IndexMembership> incoming,String l2Code) {
        if(l2Code==null || !l2Code.matches("[0-9]{6}\\.SI") || incoming.isEmpty()
                || incoming.size()>=MAX_SLICE_ROWS)
            throw new IllegalArgumentException("Nonempty bounded prepared L2 membership required");
        var before=index(existing);var supplied=index(incoming);var result=new TreeMap<>(before);
        var changes=new ArrayList<IndexMembership>();int inserted=0,revised=0,unchanged=0;
        for(var row:supplied.values()) {
            if(!row.indexCode().equals(l2Code) || !row.level().equals("L2"))
                throw new IllegalArgumentException("Prepared membership outside one L2 scope");
            var old=before.get(row.key());
            if(old==null) { inserted++;result.put(row.key(),row);changes.add(row); }
            else if(old.equals(row)) unchanged++;
            else { revised++;result.put(row.key(),row);changes.add(row); }
        }
        if(result.size()>MAX_EXISTING_ROWS) throw new IllegalArgumentException("Merged membership exceeds row budget");
        int absent=(int)before.keySet().stream().filter(k->k.indexCode().equals(l2Code) && !supplied.containsKey(k)).count();
        return new Result(new ArrayList<>(result.values()),changes,inserted,revised,unchanged,absent);
    }
    public static boolean sameBusinessValues(IndexMembership a,IndexMembership b) {
        if(a==null || b==null) return false;
        return new IndexMembership(a.indexCode(),a.tsCode(),b.observedAt(),a.indexName(),a.constituentCode(),a.constituentName(),
                a.membershipStartDate(),a.membershipEndDate(),a.latestFlag(),a.weight(),a.level(),a.l1Name(),a.l2Name(),a.l3Name()).equals(b);
    }
    private static TreeMap<IndexMembership.Key,IndexMembership> index(List<IndexMembership> rows) {
        if(rows.size()>MAX_EXISTING_ROWS) throw new IllegalArgumentException("Membership row budget exceeded");
        var result=new TreeMap<IndexMembership.Key,IndexMembership>(ORDER);
        for(var row:rows) if(result.putIfAbsent(row.key(),row)!=null)
            throw new IllegalArgumentException("Ambiguous membership period; cannot silently collapse hierarchy or versions");
        return result;
    }
}
