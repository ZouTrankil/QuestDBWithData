package com.zoutrankil.data.index.domain;
import com.zoutrankil.data.domain.*;
import java.util.*;
import java.time.*;
public final class DcIndexState {
 private DcIndexState() {}
public record Identity(long id,String directory){public Identity{if(id<0||directory==null||directory.isBlank())throw new IllegalArgumentException("dc_index table identity required");}}
public record Snapshot(Identity identity,List<DcIndex> rows,String fingerprint,int bytes){public Snapshot{Objects.requireNonNull(identity);rows=List.copyOf(rows);Objects.requireNonNull(fingerprint);}}
public record Prepared(DcIndexState.Snapshot before,DcIndexState.Snapshot current,LocalDate fromInclusive,
            LocalDate toInclusive,List<DcIndex> source,List<DcIndex> expected) {
        public Prepared{source=List.copyOf(source);expected=List.copyOf(expected);}
    }
public record Verified(String table,DcIndexState.Snapshot outsideSnapshot,String receipt) {}
public record Complete(String table,DcIndexState.Snapshot snapshot,int sourceRows,String sourceFingerprint,String receipt) {}
public record DateRange(LocalDate min,LocalDate max){public DateRange{if((min==null)!=(max==null)||min!=null&&min.isAfter(max))throw new IllegalStateException("Invalid D023 target date range");}}
}
