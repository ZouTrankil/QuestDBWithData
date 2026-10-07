package com.zoutrankil.data.index.domain;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import com.zoutrankil.data.index.domain.policy.IndexMembershipMerge;
import com.zoutrankil.data.index.domain.policy.IndexMembershipValues;
import java.util.*;

/** Immutable physical state and stage receipts; serialized field order is part of recovery evidence. */
public final class IndexMembershipState {
    private IndexMembershipState() {}
    public record Identity(long id,String directory) {}
    public record Snapshot(Identity identity,List<IndexMemberRow> rows,String fingerprint,int bytes) {
        public Snapshot { rows=List.copyOf(rows); }
        public List<IndexMembership> businessRows() { return rows.stream().map(IndexMembershipValues::fromStorage).toList(); }
    }

    public record Prepared(Snapshot before,List<IndexMembership> source,String l2Code,
                           List<IndexMemberRow> rows,IndexMembershipMerge.Result merge) {
        public Prepared { source=List.copyOf(source);rows=List.copyOf(rows); }
    }
    public record Verified(String table,Snapshot snapshot,int batches,String receipt) {}
}
