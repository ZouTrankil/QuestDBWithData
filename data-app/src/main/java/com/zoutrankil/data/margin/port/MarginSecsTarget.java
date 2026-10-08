package com.zoutrankil.data.margin.port;

import com.zoutrankil.data.margin.domain.MarginSecsState.Snapshot;

/** Immutable target configuration with fresh write sessions. */
public interface MarginSecsTarget {
    String tableName();
    String targetId();
    Snapshot snapshot();
    MarginSecsWriteSession newWriter(String physicalTargetId);
}
