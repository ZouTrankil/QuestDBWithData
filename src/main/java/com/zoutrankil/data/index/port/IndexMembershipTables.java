package com.zoutrankil.data.index.port;

import com.zoutrankil.data.index.domain.IndexMembershipState.*;

/** Physical operations; publication decisions and recovery remain in the application. */
public interface IndexMembershipTables {
    Table open(String table);
    String identify(String table,long id,String directory);
    boolean exists(String table);
    boolean walSettled(String table);
    void rename(String from,String to);
    interface Table {
        Identity preflight();
        Snapshot snapshot() throws Exception;
    }
}
