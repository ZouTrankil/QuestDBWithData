package com.zoutrankil.data.index.port;

import com.zoutrankil.data.index.domain.ThsIndexState;

import com.zoutrankil.data.index.domain.ThsIndexState.Identity;
import com.zoutrankil.data.index.domain.ThsIndexState.Snapshot;

/** Physical operations used by the application-owned THS directory publication protocol. */
public interface ThsIndexTables {
    Table open(String table);
    String identify(String table, long id, String directory);
    default String identify(String table, Identity identity) {
        return identify(table, identity.id(), identity.directory());
    }
    Snapshot snapshotIfPresent(String table, String unsettledMessage) throws Exception;
    void rename(String from, String to);

    interface Table {
        Identity preflight();
        Snapshot snapshot() throws Exception;
    }
}
