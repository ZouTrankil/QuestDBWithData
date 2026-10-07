package com.zoutrankil.data.stock.domain;
import com.zoutrankil.data.domain.StockSuspend;
import java.time.LocalDate;
import java.util.*;
/** Frozen D011 snapshots and stage receipts, independent of the database adapter. */
public final class StockSuspendState {
    private StockSuspendState() {}
    public record Identity(long id,String directory) {
        public Identity { if(id<0||directory==null||directory.isBlank()) throw new IllegalArgumentException("Physical identity required"); }
    }
    public record Snapshot(Identity identity,List<StockSuspend> rows,String fingerprint,int bytes) {
        public Snapshot { Objects.requireNonNull(identity);rows=List.copyOf(rows);Objects.requireNonNull(fingerprint); }
    }
    public record Prepared(Snapshot before,Snapshot current,LocalDate fromInclusive,LocalDate toInclusive,
                           List<StockSuspend> source,List<StockSuspend> expected) {
        public Prepared { source=List.copyOf(source);expected=List.copyOf(expected); }
        public boolean requiresWrite() { return !current.rows().equals(expected); }
    }
    public record Verified(String table,Snapshot snapshot,int batches,String receipt) {}
    public record Range(LocalDate min,LocalDate max) {
        public Range { if((min==null)!=(max==null)||min!=null&&min.isAfter(max)) throw new IllegalStateException("Invalid stk_suspend physical date range"); }
    }
}
