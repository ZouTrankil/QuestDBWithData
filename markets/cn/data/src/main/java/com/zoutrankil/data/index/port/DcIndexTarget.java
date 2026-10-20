package com.zoutrankil.data.index.port;

import com.zoutrankil.data.index.domain.DcIndexState;

public interface DcIndexTarget {
    String tableName();

    String targetId();

    String physicalTargetId();

    DcIndexState.DateRange range();

    DcIndexWriteSession newWriter(String physicalId);

    DcIndexWriteSession stageWriter(String stage, String physicalId);

    DcIndexTables newPublicationTables();

    DcIndexStagingPort newStaging();
}
