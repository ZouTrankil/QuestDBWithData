package com.zoutrankil.data.index.domain;

import com.zoutrankil.data.domain.ThsIndex;
import com.zoutrankil.data.domain.table.ThsIndexRow;
import com.zoutrankil.data.index.domain.policy.ThsIndexMerge;
import java.util.*;

/** Immutable values shared by THS application protocols and storage implementations. */
public final class ThsIndexState {
    private ThsIndexState() {}
    public static final int MAX_ROWS=5000,MAX_BYTES=8*1024*1024;
    public record Identity(long id,String directory) {}
    public record Snapshot(Identity identity,List<ThsIndexRow> rows,String fingerprint,int bytes) {
        public Snapshot { rows=List.copyOf(rows); }
        public List<ThsIndex> businessRows() {
            return rows.stream().map(ThsIndexRows::fromStorage).toList();
        }
    }
    public record Scope(String code,String exchange,String type) {
        public Scope {
            if(code!=null) {
                if(!ThsIndex.validCode(code) || exchange!=null || type!=null)
                    throw new IllegalArgumentException("Choose one exact THS code or one market/type scope");
            } else if((exchange!=null || type!=null)
                    && (exchange==null || type==null || !ThsIndex.EXCHANGES.contains(exchange) || !ThsIndex.TYPES.contains(type)))
                throw new IllegalArgumentException("Explicit THS market and index type required");
        }
        public static Scope all() { return new Scope(null,null,null); }
        public Map<String,Object> parameters() {
            return code!=null?Map.of("ts_code",code):exchange!=null?Map.of("exchange",exchange,"type",type):Map.of();
        }
        public void requireContains(ThsIndex row) {
            if(code!=null?!code.equals(row.tsCode()):exchange!=null
                    && (!exchange.equals(row.exchange()) || !type.equals(row.indexType())))
                throw new IllegalArgumentException("THS response outside frozen source scope");
        }
    }
    public record Prepared(ThsIndexState.Snapshot before,List<ThsIndex> source,ThsIndexState.Scope scope,
                           List<ThsIndexRow> rows,ThsIndexMerge.Result merge) {
        public Prepared { source=List.copyOf(source);rows=List.copyOf(rows); }
    }
    public record Verified(String table,ThsIndexState.Snapshot snapshot,String receipt,int batches) {}
}
