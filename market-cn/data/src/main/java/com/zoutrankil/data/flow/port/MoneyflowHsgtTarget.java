package com.zoutrankil.data.flow.port;

import com.zoutrankil.data.flow.domain.MoneyflowHsgtState.Snapshot;

/** Configured target; mutable write and publication sessions are created per operation. */
public interface MoneyflowHsgtTarget {
    String tableName();
    String targetId();
    String physicalTargetId() throws Exception;
    Snapshot snapshot() throws Exception;
    MoneyflowHsgtWriteSession newWriter(String physicalTargetId);
    MoneyflowHsgtStagingPort newTables();
}
