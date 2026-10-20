package com.zoutrankil.data.index.port;

import com.zoutrankil.data.domain.index.IndexMembership;
import com.zoutrankil.data.index.domain.IndexMembershipState.Prepared;
import com.zoutrankil.data.index.domain.IndexMembershipState.Snapshot;

import java.util.List;

/** Configured target and factories for per-operation storage collaborators. */
public interface IndexMembershipTarget extends IndexMembershipTables {
    String tableName();

    IndexMembershipTables publicationTables();

    Prepared prepare(Snapshot before, List<IndexMembership> source, String l2Code) throws Exception;

    Prepared preparePrepared(Snapshot before, List<IndexMembership> source, String l2Code)
            throws Exception;

    IndexMembershipStagingPort newStaging();
}
