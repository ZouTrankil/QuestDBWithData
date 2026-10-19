package com.zoutrankil.data.index.port;

import com.zoutrankil.data.index.domain.ThsMemberState;

import com.zoutrankil.data.index.domain.ThsMemberState.Identity;
import com.zoutrankil.data.index.domain.ThsMemberState.Snapshot;

/** Physical board snapshots keep unaffected-board evidence in the same read. */
public interface ThsMemberTables {
    Table open(String table);
    String identify(String table, long id, String directory);
    default String identify(String table, Identity identity) {
        return identify(table, identity.id(), identity.directory());
    }
    Snapshot snapshotIfPresent(String table, String board) throws Exception;
    void rename(String from, String to);

    interface Table {
        Identity preflight();
        Snapshot snapshot(String board) throws Exception;
    }
}
