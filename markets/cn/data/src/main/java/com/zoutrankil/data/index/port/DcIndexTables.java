package com.zoutrankil.data.index.port;

import com.zoutrankil.data.index.domain.DcIndexState;

public interface DcIndexTables {
    interface Table {
        DcIndexState.Identity preflight();

        DcIndexState.Snapshot snapshot() throws Exception;
    }

    Table open(String table);

    String logicalTargetId(String table);

    String physicalTargetId(String table, DcIndexState.Identity identity);

    DcIndexState.Snapshot snapshotIfPresent(String table) throws Exception;

    void rename(String from, String to);
}
