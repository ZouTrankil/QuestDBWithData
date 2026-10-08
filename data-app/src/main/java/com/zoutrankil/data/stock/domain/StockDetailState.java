package com.zoutrankil.data.stock.domain;
import com.zoutrankil.data.domain.StockDetailInfo;
import com.zoutrankil.data.domain.table.StockDetailInfoRow;
import com.zoutrankil.data.stock.domain.policy.StockDetailInfoMerge;
import com.zoutrankil.data.stock.domain.policy.StockDetailRows;
import java.util.List;
/** Frozen physical values and publication inputs; component order is the persisted evidence contract. */
public final class StockDetailState {
    private StockDetailState() {}
    public record Identity(long id,String directory) {}
    public record Snapshot(Identity identity,List<StockDetailInfoRow> rows,String fingerprint,int bytes) {
        public Snapshot { rows=List.copyOf(rows); }
        public List<StockDetailInfo> businessRows() { return rows.stream().map(StockDetailRows::fromStorage).toList(); }
    }
    public record Prepared(Snapshot before,List<StockDetailInfoRow> rows,StockDetailInfoMerge.Result merge) {
        public Prepared { rows=List.copyOf(rows); }
    }
    public record Verified(String table,Snapshot snapshot,String receipt) {}
}
