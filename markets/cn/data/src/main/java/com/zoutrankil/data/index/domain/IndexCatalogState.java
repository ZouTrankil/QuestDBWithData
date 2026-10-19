package com.zoutrankil.data.index.domain;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import com.zoutrankil.data.index.domain.policy.IndexCatalogMerge;
import com.zoutrankil.data.index.domain.policy.IndexCatalogValues;
import java.util.*;

/** Immutable physical state and stage receipts; serialized field order is part of recovery evidence. */
public final class IndexCatalogState {
    private IndexCatalogState() {}
    public record Identity(long id,String directory) {}
    public record Snapshot(Identity identity,List<IndexRow> rows,String fingerprint,int bytes) {
        public Snapshot { rows=List.copyOf(rows); }
        public List<IndexCatalogEntry> businessRows() {
            return rows.stream().map(IndexCatalogValues::fromStorage).toList();
        }
    }

    public record Prepared(Snapshot before,List<IndexRow> rows,IndexCatalogMerge.Result merge) {
        public Prepared { rows=List.copyOf(rows); }
    }
    public record Verified(String table,Snapshot snapshot,String receipt,int batches) {}
}
